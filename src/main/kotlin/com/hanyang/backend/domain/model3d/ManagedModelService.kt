package com.hanyang.backend.domain.model3d

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.media.*
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.unit.UnitService
import com.hanyang.backend.security.CurrentUser
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.LocalDateTime

/** Uses the existing six-stage pipeline, while checking the owning unit and an explicit model ID. */
@Service
class ManagedModelService(
    private val units: UnitService, private val rooms: RoomRepository,
    private val models: Room3DModelRepository, private val assets: MediaAssetRepository,
    private val pipeline: Room3DModelService, private val currentUser: CurrentUser,
    private val json: JsonMapper, private val env: Environment
) {
    @Transactional(readOnly = true)
    fun list(spaceId: Long): List<ManagedModelView> {
        val room = requireSpace(spaceId)
        return models.findTop100ByRoomIdOrderByUploadedAtDescIdDesc(spaceId).map { view(it, requireNotNull(room.unit?.id)) }
    }

    @Transactional(readOnly = true)
    fun get(spaceId: Long, modelId: Long): ManagedModelView {
        val room = requireSpace(spaceId)
        return view(requireModel(spaceId, modelId), requireNotNull(room.unit?.id))
    }

    @Transactional
    fun analyze(spaceId: Long, request: ManagedAnalyzeRequest): ManagedModelView {
        requirePipeline(requireAnalyzeDispatcher = true)
        val room = requireSpace(spaceId)
        val unitId = requireNotNull(room.unit?.id)
        if (request.imageAssetIds.size != AiPayloadLimits.SOURCE_IMAGE_COUNT) {
            invalid("INVALID_SOURCE_IMAGE_COUNT", "같은 공간을 촬영한 사진 4장을 선택해 주세요.")
        }
        val images = validateImages(unitId, request.imageAssetIds, required = true)
        if (images.any { it.checksum.isNullOrBlank() }) {
            invalid("SOURCE_IMAGE_CHECKSUM_REQUIRED", "원본 사진을 확인할 수 없습니다. 사진 4장을 다시 업로드해 주세요.")
        }
        if (images.map { it.checksum }.distinct().size != AiPayloadLimits.SOURCE_IMAGE_COUNT) {
            invalid("DUPLICATE_SOURCE_IMAGES", "같은 사진을 반복해서 사용할 수 없습니다. 서로 다른 시점의 사진 4장을 선택해 주세요.")
        }
        val origin = backendOrigin()
        val result = pipeline.analyzeRoom(spaceId, AnalyzeModelRequest(request.imageAssetIds.map {
            "$origin/api/internal/ai/assets/$it/content"
        }), webhookOverride = "$origin/api/internal/ai/webhook")
        val model = requireModel(spaceId, requireNotNull(result.modelId))
        model.sourceType = ModelSourceType.AI_PIPELINE
        model.sourceImageAssetIds = encodeIds(request.imageAssetIds)
        return view(model, unitId)
    }

    @Transactional
    fun reconstruct(spaceId: Long, modelId: Long, request: CorrectionRequestDto): ManagedModelView {
        requirePipeline(requireAnalyzeDispatcher = true)
        val room = requireSpace(spaceId)
        val model = requireModel(spaceId, modelId)
        if (model.sourceType == ModelSourceType.MANUAL_UPLOAD) conflict("MANUAL_MODEL", "직접 업로드한 모델에는 AI 재구성을 요청할 수 없습니다.")
        if (request.vertices.size > AiPayloadLimits.MAX_VERTICES || request.edges.size > AiPayloadLimits.MAX_EDGES) invalid("TOO_MANY_COORDINATES", "좌표 데이터 크기를 줄여 주세요.")
        fun isUuid(value: String) = runCatching { java.util.UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
        if (request.vertices.any { !isUuid(it.id) || (it.sourceVertexId != null && !isUuid(it.sourceVertexId)) } ||
            request.edges.any { !isUuid(it.id) || !isUuid(it.startVertexId) || !isUuid(it.endVertexId) }) {
            invalid("INVALID_COORDINATE_ID", "AI 좌표와 간선 ID는 UUID 형식을 사용해 주세요.")
        }
        val imageCount = decodeIds(model.sourceImageAssetIds).size
        if (model.sourceType == ModelSourceType.AI_PIPELINE && request.vertices.any { it.imageIndex == null || it.imageIndex !in 0 until imageCount }) {
            invalid("INVALID_IMAGE_INDEX", "보정 좌표에는 원본 사진에 해당하는 imageIndex가 필요합니다.")
        }
        if (model.sourceType == ModelSourceType.AI_PIPELINE && model.status == ModelStatus.WAITING_FOR_CORRECTION) {
            validatePhotoCorrection(model, request)
        }
        pipeline.applyCorrectionAndReconstruct(spaceId, request, modelId)
        return view(model, requireNotNull(room.unit?.id))
    }

    /** Validate against immutable, server-verified photo dimensions before changing state or enqueuing.
     * No remote media I/O is added to this transaction; legacy native corrections stay separate.
     */
    private fun validatePhotoCorrection(model: Room3DModel, request: CorrectionRequestDto) {
        if (request.vertices.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 0 || it.y < 0 || it.z != 0.0 }) {
            invalid("INVALID_IMAGE_COORDINATES", "사진 보정 좌표는 사진 안의 X/Y와 Z=0을 사용해 주세요.")
        }
        val images = validateImages(requireNotNull(model.room.unit?.id), decodeIds(model.sourceImageAssetIds), required = true)
        val dimensions = images.map { image ->
            val width = image.pixelWidth
            val height = image.pixelHeight
            if (width == null || height == null || width <= 0 || height <= 0) {
                conflict("SOURCE_IMAGE_METADATA_REQUIRED", "원본 사진의 크기 정보가 없습니다. 사진 정보를 갱신한 뒤 다시 시도해 주세요.")
            }
            width to height
        }
        if (request.vertices.any { vertex ->
            val (width, height) = dimensions[requireNotNull(vertex.imageIndex)]
            vertex.x >= width || vertex.y >= height
        }) {
            invalid("IMAGE_COORDINATES_OUT_OF_BOUNDS", "보정 좌표를 원본 사진의 폭과 높이 안으로 입력해 주세요.")
        }
        val payload = model.roomData?.let { json.readValue(it, Map::class.java) }
        val initial = (payload?.get("aiVertices") ?: payload?.get("vertices")) as? List<*>
        val originals = initial.orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["imageIndex"] is Number }.associateBy { it["id"] }
        if (originals.isNotEmpty()) {
            request.vertices.forEach { vertex ->
                val original = originals[vertex.id]
                if (original == null) {
                    if (vertex.sourceVertexId != null) {
                        invalid("INVALID_SOURCE_VERTEX", "추가한 점은 기존 원본 점 연결을 임의로 지정할 수 없습니다.")
                    }
                } else if ((original["imageIndex"] as Number).toInt() != vertex.imageIndex ||
                    original["sourceVertexId"] != vertex.sourceVertexId) {
                    invalid("SOURCE_VERTEX_MISMATCH", "보정 점의 원본 사진과 원본 점 연결을 유지해 주세요.")
                }
            }
        }
        val vertices = request.vertices.associateBy { it.id }
        request.edges.forEach { edge ->
            val start = vertices[edge.startVertexId]
            val end = vertices[edge.endVertexId]
            if (start != null && end != null && start.imageIndex != end.imageIndex) {
                invalid("CROSS_IMAGE_EDGE", "하나의 간선은 같은 원본 사진의 점끼리 연결해 주세요.")
            }
        }
    }

    // Validate ownership in a short read transaction; refresh performs the remote call outside it.
    fun refresh(spaceId: Long, modelId: Long): ManagedModelView {
        requirePipeline()
        val existing = get(spaceId, modelId)
        if (existing.sourceType == ModelSourceType.MANUAL_UPLOAD) return existing
        try { pipeline.refreshFromAi(spaceId, modelId) } catch (e: ApiException) { throw e } catch (_: Exception) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 서버 상태를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
        return get(spaceId, modelId)
    }

    @Transactional(readOnly = true)
    fun validateUploadTarget(unitId: Long, spaceId: Long, modelId: Long?, sourceImages: List<Long>): List<Long> {
        requireSpace(spaceId, unitId)
        if (modelId == null) {
            validateImages(unitId, sourceImages)
            return sourceImages
        }
        val model = requireModel(spaceId, modelId)
        if (model.sourceType == ModelSourceType.MANUAL_UPLOAD || model.status != ModelStatus.WAITING_FOR_MODEL_UPLOAD) {
            conflict("MODEL_NOT_READY_FOR_UPLOAD", "해당 AI 작업의 최종 좌표 생성이 끝난 뒤 GLB를 업로드해 주세요.")
        }
        val expected = decodeIds(model.sourceImageAssetIds)
        if (sourceImages.isNotEmpty() && sourceImages != expected) invalid("SOURCE_IMAGES_MISMATCH", "AI 작업의 원본 사진과 일치하지 않습니다.")
        validateImages(unitId, expected)
        return expected
    }

    /** Called inside the upload-session lock transaction: model, asset and session commit together. */
    @Transactional
    fun completeVerifiedUpload(unitId: Long, spaceId: Long, modelId: Long?, sourceImages: List<Long>, createAsset: (Long) -> MediaAsset): MediaAsset {
        val room = requireSpace(spaceId, unitId)
        validateImages(unitId, sourceImages)
        val model = if (modelId == null) models.saveAndFlush(Room3DModel(
            room = room, sourceType = ModelSourceType.MANUAL_UPLOAD, sourceImageAssetIds = encodeIds(sourceImages),
            originalImageUrl = sourceImages.firstOrNull()?.let { "/api/v2/media/assets/$it/content" },
            status = ModelStatus.SUCCESS
        )) else models.findByIdAndRoomId(modelId, spaceId).orElseThrow { missingModel() }
        if (modelId != null) {
            if (model.sourceType == ModelSourceType.MANUAL_UPLOAD || model.status != ModelStatus.WAITING_FOR_MODEL_UPLOAD) {
                conflict("MODEL_NOT_READY_FOR_UPLOAD", "이미 완료되었거나 업로드 대기 중이 아닌 AI 모델입니다.")
            }
            if (decodeIds(model.sourceImageAssetIds) != sourceImages) invalid("SOURCE_IMAGES_MISMATCH", "모델 원본 사진이 일치하지 않습니다.")
        }
        val asset = createAsset(requireNotNull(model.id))
        if (modelId == null) {
            model.modelUrl = "/api/v2/media/assets/${asset.id}/content"
            model.format = "glb"
            model.fileSizeMb = BigDecimal(asset.sizeBytes).divide(BigDecimal(1048576), 2, RoundingMode.HALF_UP)
        } else {
            pipeline.completeVerifiedModelUpload(spaceId, modelId, requireNotNull(asset.id), asset.sizeBytes)
        }
        models.saveAndFlush(model)
        return asset
    }

    @Transactional(readOnly = true)
    fun sourceSpaceId(modelId: Long?, unitId: Long): Long? {
        if (modelId == null) return null
        val model = models.findById(modelId).orElseThrow { missingModel() }
        if (model.room.unit?.id != unitId || model.room.owner.id != currentUser.id()) forbidden()
        return model.room.id
    }

    fun encodeIds(ids: List<Long>): String = json.writeValueAsString(ids)
    fun decodeIds(raw: String?): List<Long> = if (raw == null) emptyList() else
        json.readTree(raw).map { it.asLong() }

    private fun requireSpace(spaceId: Long, unitId: Long? = null): Room {
        val room = rooms.findById(spaceId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND", "공간을 찾을 수 없습니다.") }
        val linkedUnitId = room.unit?.id ?: throw ApiException(HttpStatus.CONFLICT, "SPACE_NOT_LINKED", "호실에 연결된 공간을 사용해 주세요.")
        units.requireManagedUnit(linkedUnitId)
        if (room.owner.id != currentUser.id() || (unitId != null && linkedUnitId != unitId)) forbidden()
        return room
    }
    private fun requireModel(spaceId: Long, modelId: Long): Room3DModel = models.findById(modelId).orElseThrow { missingModel() }.also {
        if (it.room.id != spaceId) throw missingModel()
    }
    private fun validateImages(unitId: Long, ids: List<Long>, required: Boolean = false): List<MediaAsset> {
        if ((required && ids.isEmpty()) || ids.size > 20 || ids.distinct().size != ids.size || ids.any { it <= 0 }) {
            invalid("INVALID_SOURCE_IMAGES", "원본 사진은 중복 없이 1~20개의 파일 ID로 전달해 주세요.")
        }
        return ids.map { id ->
            val asset = assets.findById(id).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "SOURCE_IMAGE_NOT_FOUND", "원본 사진을 찾을 수 없습니다.") }
            if (asset.unitId != unitId || asset.ownerUserId != currentUser.id() || asset.kind != MediaKind.IMAGE || asset.status != MediaStatus.VERIFIED) {
                invalid("INVALID_SOURCE_IMAGE", "해당 호실에 검증된 본인 사진만 원본으로 사용할 수 있습니다.")
            }
            asset
        }
    }
    private fun view(model: Room3DModel, unitId: Long): ManagedModelView {
        val asset = assets.findFirstBySourceModelIdAndUnitIdAndStatusOrderByIdAsc(requireNotNull(model.id), unitId, MediaStatus.VERIFIED)
        return ManagedModelView(requireNotNull(model.id), requireNotNull(model.room.id), unitId, model.status, model.sourceType,
            decodeIds(model.sourceImageAssetIds), asset?.id, asset?.let { "/api/v2/media/assets/${it.id}/content" },
            model.roomData?.let { json.readValue(it, Any::class.java) }, model.uploadedAt,
            model.status == ModelStatus.SUCCESS && asset == null && model.sourceType == ModelSourceType.LEGACY_AI)
    }
    private fun requirePipeline(requireAnalyzeDispatcher: Boolean = false) {
        if (!env.getProperty("app.ai.pipeline-enabled", Boolean::class.java, false) || env.getProperty("app.ai.service-key", "").toByteArray().size < 32) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PIPELINE_NOT_CONFIGURED", "AI 분석 연결이 설정되지 않았습니다. 운영 설정을 확인해 주세요.")
        }
        backendOrigin()
        if (requireAnalyzeDispatcher && (!env.getProperty("outbox.enabled",Boolean::class.java,true) || !env.getProperty("outbox.worker.enabled",Boolean::class.java,false))) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_DISPATCHER_NOT_CONFIGURED","AI 요청을 영속적으로 전송할 Outbox와 Worker를 활성화해 주세요.")
        }
    }
    private fun backendOrigin(): String {
        val raw = env.getProperty("app.ai.backend-base-url", "").trim().trimEnd('/')
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank() || uri?.rawUserInfo != null || uri?.rawQuery != null || uri?.rawFragment != null || !uri?.path.isNullOrEmpty()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PIPELINE_NOT_CONFIGURED", "AI가 접근할 백엔드 주소를 설정해 주세요.")
        }
        return raw
    }
    private fun missingModel() = ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "해당 공간의 모델을 찾을 수 없습니다.")
    private fun forbidden(): Nothing = throw ApiException(HttpStatus.FORBIDDEN, "SPACE_FORBIDDEN", "공간 관리 권한이 없습니다.")
    private fun invalid(code: String, message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, code, message)
    private fun conflict(code: String, message: String): Nothing = throw ApiException(HttpStatus.CONFLICT, code, message)
}
