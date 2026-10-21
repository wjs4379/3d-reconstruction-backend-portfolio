package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.listing.ListingRepository
import com.hanyang.backend.domain.listing.ListingStatus
import com.hanyang.backend.domain.model3d.ModelStatus
import com.hanyang.backend.domain.model3d.ManagedModelService
import com.hanyang.backend.common.PageView
import com.hanyang.backend.common.pageRequest
import com.hanyang.backend.domain.model3d.Room3DModelRepository
import com.hanyang.backend.domain.unit.UnitService
import com.hanyang.backend.security.CurrentUser
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

@Service
class MediaService(
    private val assets: MediaAssetRepository, private val sessions: UploadSessionRepository,
    private val units: UnitService, private val listings: ListingRepository, private val models: Room3DModelRepository,
    private val user: CurrentUser, private val storage: MediaStorage, private val validation: MediaValidation, private val guard: MediaConcurrencyGuard,
    manager: PlatformTransactionManager, private val env: Environment, private val managedModels: ManagedModelService
) {
    private val tx = TransactionTemplate(manager)
    private fun limit(kind: MediaKind) = if (kind == MediaKind.IMAGE) 5_242_880L else 26_214_400L
    fun create(request: UploadSessionRequest): UploadSessionView {
        units.requireManagedUnit(request.unitId)
        if (request.sizeBytes !in 1..limit(request.kind)) throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_FILE_SIZE","파일 크기를 확인해 주세요.")
        val allowed = if (request.kind == MediaKind.IMAGE) setOf("image/png","image/jpeg") else setOf("model/gltf-binary")
        if (request.contentType !in allowed) throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_FILE_TYPE","JPEG·PNG 사진 또는 GLB 모델을 사용해 주세요.")
        val sourceImages = if (request.kind == MediaKind.MODEL) {
            val spaceId = request.spaceId ?: throw ApiException(HttpStatus.BAD_REQUEST,"MODEL_SPACE_REQUIRED","모델을 연결할 공간 ID가 필요합니다.")
            managedModels.validateUploadTarget(request.unitId,spaceId,request.modelId,request.sourceImageAssetIds)
        } else {
            if (request.spaceId != null || request.modelId != null || request.sourceImageAssetIds.isNotEmpty()) throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_IMAGE_MODEL_FIELDS","사진 업로드에는 모델 연결 필드를 전달하지 마세요.")
            emptyList()
        }
        val id = UUID.randomUUID().toString()
        val session = UploadSession(id=id,unitId=request.unitId,ownerUserId=user.id(),kind=request.kind,
            stagingKey="staging/${request.unitId}/$id",contentType=request.contentType,sizeBytes=request.sizeBytes,
            expiresAt=LocalDateTime.now().plusMinutes(10),spaceId=request.spaceId,modelId=request.modelId,
            sourceImageAssetIds=managedModels.encodeIds(sourceImages))
        sessions.save(session)
        return UploadSessionView(id,"/api/v2/media/upload-sessions/$id/content",
            headers=mapOf("Content-Type" to session.contentType),expiresAt=session.expiresAt)
    }
    fun upload(id: String, contentType: String?, input: InputStream) = guard.run { uploadBounded(id,contentType,input) }
    private fun uploadBounded(id: String, contentType: String?, input: InputStream) {
        val session = ownedSession(id)
        if (session.assetId != null) throw ApiException(HttpStatus.CONFLICT,"UPLOAD_COMPLETED","완료된 업로드입니다.")
        checkExpiry(session)
        if (contentType?.substringBefore(';') != session.contentType) throw ApiException(HttpStatus.BAD_REQUEST,"FILE_TYPE_MISMATCH","발급된 파일 형식과 일치하지 않습니다.")
        val bytes = readLimited(input,session.sizeBytes)
        checkSize(session,bytes)
        storageCall { storage.putStaging(session.stagingKey,bytes,session.contentType) }
    }
    fun complete(id: String): MediaAssetView = guard.run { completeBounded(id) }
    private fun completeBounded(id: String): MediaAssetView {
        val session = ownedSession(id)
        session.assetId?.let { return assetView(assets.findById(it).orElseThrow { missing() }) }
        checkExpiry(session)
        // Validate a captured copy, then write a fresh immutable key. A staging re-upload can never replace a published asset.
        val bytes = storageCall { storage.read(session.stagingKey,limit=session.sizeBytes) }
        checkSize(session,bytes)
        val dimensions = validation.validate(session.kind,session.contentType,bytes)
        val stored = storageCall { storage.putFinal("assets/${session.unitId}/${UUID.randomUUID()}",bytes,session.contentType) }
        var retained = false
        try {
            val asset = tx.execute {
                val locked = sessions.findLocked(id) ?: throw missing()
                if (locked.ownerUserId != user.id()) forbidden()
                units.requireManagedUnit(locked.unitId)
                locked.assetId?.let { return@execute assets.findById(it).orElseThrow { missing() } }
                checkExpiry(locked)
                val result = if (locked.kind == MediaKind.MODEL) {
                    val spaceId = locked.spaceId ?: throw ApiException(HttpStatus.CONFLICT,"MODEL_SPACE_REQUIRED","공간 정보가 없는 이전 업로드 세션은 다시 발급해 주세요.")
                    managedModels.completeVerifiedUpload(locked.unitId,spaceId,locked.modelId,managedModels.decodeIds(locked.sourceImageAssetIds)) { modelId ->
                        assets.saveAndFlush(newAsset(locked.unitId,locked.kind,stored,bytes,locked.contentType,modelId))
                    }
                } else assets.saveAndFlush(newAsset(locked.unitId,locked.kind,stored,bytes,locked.contentType,dimensions=dimensions))
                locked.assetId = result.id; sessions.saveAndFlush(locked)
                result
            } ?: error("Upload transaction returned no asset")
            retained = asset.objectKey == stored.key
            runCatching { storage.delete(session.stagingKey) }
            return assetView(asset)
        } finally { if (!retained) runCatching { storage.delete(stored.key) } }
    }
    /** Recover old verified image extents without replacing the asset or holding a DB lock during storage I/O. */
    fun repairImageMetadata(assetId: Long): MediaImageMetadataView = guard.run {
        val captured = assets.findById(assetId).orElseThrow { missing() }
        requireOwnedVerifiedImage(captured)
        if (captured.pixelWidth != null && captured.pixelHeight != null) {
            return@run MediaImageMetadataView(assetId,requireNotNull(captured.pixelWidth),requireNotNull(captured.pixelHeight))
        }
        if (captured.checksum.isNullOrBlank()) {
            throw ApiException(HttpStatus.CONFLICT,"IMAGE_METADATA_INTEGRITY_REQUIRED","원본 파일의 검증 기록이 없어 사진을 다시 업로드해 주세요.")
        }
        val bytes = storageCall { storage.read(captured.objectKey,captured.objectVersion,limit(MediaKind.IMAGE)) }
        if (bytes.size.toLong() != captured.sizeBytes || checksum(bytes) != captured.checksum) {
            throw ApiException(HttpStatus.CONFLICT,"IMAGE_METADATA_INTEGRITY_MISMATCH","원본 파일의 검증 기록과 일치하지 않아 사진 크기를 복구할 수 없습니다.")
        }
        val dimensions = requireNotNull(validation.validate(MediaKind.IMAGE,captured.contentType,bytes))
        tx.execute {
            val locked = assets.findLocked(assetId) ?: throw missing()
            requireOwnedVerifiedImage(locked)
            if (locked.objectKey != captured.objectKey || locked.objectVersion != captured.objectVersion ||
                locked.checksum != captured.checksum || locked.sizeBytes != captured.sizeBytes ||
                locked.contentType != captured.contentType || locked.unitId != captured.unitId) {
                throw ApiException(HttpStatus.CONFLICT,"IMAGE_METADATA_INTEGRITY_MISMATCH","원본 파일의 검증 기록이 변경되어 다시 확인해 주세요.")
            }
            if ((locked.pixelWidth != null && locked.pixelWidth != dimensions.width) ||
                (locked.pixelHeight != null && locked.pixelHeight != dimensions.height)) {
                throw ApiException(HttpStatus.CONFLICT,"IMAGE_METADATA_CONFLICT","이미 저장된 사진 크기와 일치하지 않습니다.")
            }
            locked.pixelWidth = dimensions.width
            locked.pixelHeight = dimensions.height
            assets.saveAndFlush(locked)
            MediaImageMetadataView(assetId,dimensions.width,dimensions.height)
        } ?: error("Image metadata transaction returned no result")
    }
    private fun requireOwnedVerifiedImage(asset: MediaAsset) {
        units.requireManagedUnit(asset.unitId)
        if (asset.ownerUserId != user.id() || asset.status != MediaStatus.VERIFIED) forbidden()
        if (asset.kind != MediaKind.IMAGE) {
            throw ApiException(HttpStatus.BAD_REQUEST,"IMAGE_METADATA_IMAGE_REQUIRED","사진 파일의 크기만 복구할 수 있습니다.")
        }
    }
    fun importModel(modelId: Long, unitId: Long): MediaAssetView = guard.run { importModelBounded(modelId,unitId) }
    private fun importModelBounded(modelId: Long, unitId: Long): MediaAssetView {
        units.requireManagedUnit(unitId)
        val approved = env.getProperty("app.media.approved-legacy-model-ids", "").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        if (modelId !in approved) throw ApiException(HttpStatus.CONFLICT,"MODEL_REVIEW_REQUIRED","기존 모델의 파일 소유권 확인 후 운영자가 이관 대상으로 승인해야 합니다.")
        val source = tx.execute {
            val model = models.findById(modelId).orElseThrow { missing() }
            if (model.room.unit?.id != unitId || model.room.owner.id != user.id()) forbidden()
            if (model.status != ModelStatus.SUCCESS || model.modelUrl == null) throw ApiException(HttpStatus.CONFLICT,"MODEL_NOT_COMPLETE","완료된 모델만 이관할 수 있습니다.")
            requireNotNull(model.modelUrl)
        } ?: throw missing()
        val bytes = storageCall { storage.importModel(source,limit(MediaKind.MODEL)) }
        validation.validate(MediaKind.MODEL,"model/gltf-binary",bytes)
        val stored = storageCall { storage.putFinal("assets/$unitId/${UUID.randomUUID()}",bytes,"model/gltf-binary") }
        try {
            return assetView(assets.saveAndFlush(newAsset(unitId,MediaKind.MODEL,stored,bytes,"model/gltf-binary",modelId)))
        } catch (e: Exception) { runCatching { storage.delete(stored.key) }; throw e }
    }
    fun publicRead(listingId: Long, assetId: Long): MediaRead {
        val asset = tx.execute {
            val listing = listings.findById(listingId).orElseThrow { missing() }
            if (listing.status != ListingStatus.PUBLISHED || (assetId !in listing.photoAssetIds && listing.modelAssetId != assetId && listing.modelSelections.none { it.modelAssetId == assetId })) throw missing()
            val a = assets.findById(assetId).orElseThrow { missing() }
            if (a.unitId != listing.unit.id || a.status != MediaStatus.VERIFIED) throw missing()
            a
        } ?: throw missing()
        return readAsset(asset)
    }
    fun privateRead(assetId: Long): MediaRead {
        val asset = assets.findById(assetId).orElseThrow { missing() }
        units.requireManagedUnit(asset.unitId)
        if (asset.ownerUserId != user.id() || asset.status != MediaStatus.VERIFIED) forbidden()
        return readAsset(asset)
    }
    fun mine(unitId: Long, page: Int, size: Int): PageView<MediaAssetView> {
        units.requireManagedUnit(unitId)
        return PageView.from(assets.findByUnitIdAndOwnerUserIdAndStatus(unitId,user.id(),MediaStatus.VERIFIED,pageRequest(page,size)).map(::assetView))
    }
    /** AI receives only verified image bytes, never a temporary S3 redirect. Service-key filter protects this route. */
    fun internalImageRead(assetId: Long): MediaRead {
        val asset = assets.findById(assetId).orElseThrow { missing() }
        if (asset.kind != MediaKind.IMAGE || asset.status != MediaStatus.VERIFIED) throw missing()
        return storageCall { MediaRead(asset.contentType,bytes=storage.read(asset.objectKey,asset.objectVersion,limit(MediaKind.IMAGE))) }
    }
    private fun assetView(a: MediaAsset): MediaAssetView = MediaAssetView.from(a,managedModels.sourceSpaceId(a.sourceModelId,a.unitId))
    private fun readAsset(a: MediaAsset): MediaRead = storageCall {
        val redirect = storage.signedRead(a.objectKey,a.objectVersion)
        if (redirect != null) MediaRead(a.contentType,redirect=redirect)
        else MediaRead(a.contentType,bytes=storage.read(a.objectKey,a.objectVersion,limit(a.kind)))
    }
    private fun ownedSession(id: String): UploadSession {
        val s = sessions.findById(id).orElseThrow { missing() }
        if (s.ownerUserId != user.id()) forbidden()
        units.requireManagedUnit(s.unitId)
        return s
    }
    private fun checkExpiry(s: UploadSession) { if (!LocalDateTime.now().isBefore(s.expiresAt)) throw ApiException(HttpStatus.GONE,"UPLOAD_EXPIRED","업로드 유효 시간이 지났습니다.") }
    private fun checkSize(s: UploadSession, bytes: ByteArray) { if (bytes.size.toLong() != s.sizeBytes) throw ApiException(HttpStatus.BAD_REQUEST,"FILE_SIZE_MISMATCH","발급된 파일 크기와 일치하지 않습니다.") }
    private fun newAsset(unitId: Long,kind: MediaKind,stored: StoredObject,bytes: ByteArray,type: String,sourceModelId: Long?=null,dimensions: VerifiedImageDimensions?=null) = MediaAsset(unitId=unitId,ownerUserId=user.id(),kind=kind,
        objectKey=stored.key,objectVersion=stored.version,sizeBytes=bytes.size.toLong(),contentType=type,
        checksum=checksum(bytes),sourceModelId=sourceModelId,
        pixelWidth=dimensions?.width,pixelHeight=dimensions?.height)
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun missing() = ApiException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND","파일을 찾을 수 없습니다.")
    private fun forbidden(): Nothing = throw ApiException(HttpStatus.FORBIDDEN,"ASSET_FORBIDDEN","파일 관리 권한이 없습니다.")
    private fun <T> storageCall(block: () -> T): T = try { block() } catch (e: ApiException) { throw e } catch (_: Exception) {
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MEDIA_STORAGE_UNAVAILABLE","파일 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.")
    }
}
