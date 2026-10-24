package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.listing.*
import com.hanyang.backend.domain.model3d.*
import com.hanyang.backend.domain.location.LocationService
import com.hanyang.backend.domain.location.KakaoLocationProvider
import com.hanyang.backend.domain.room.AiWebhookPhase
import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.room.RenderStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.unit.*
import com.hanyang.backend.domain.user.*
import com.hanyang.backend.security.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import javax.imageio.ImageIO

@DataJpaTest
@TestPropertySource(properties=["app.ai.pipeline-enabled=true","app.ai.service-key=test-only-ai-service-secret-at-least-32-bytes","app.ai.backend-base-url=http://localhost:8080","outbox.worker.enabled=true"])
@Import(LocationService::class,KakaoLocationProvider::class,ManagedModelService::class,Room3DModelService::class,AiServerClient::class,MediaConcurrencyGuard::class,MediaService::class,MediaValidation::class,MediaConfiguration::class,UnitService::class,ListingService::class,CurrentUser::class,MediaWorkflowIntegrationTest.JsonConfig::class)
class MediaWorkflowIntegrationTest @Autowired constructor(
    private val media: MediaService, private val users: UserRepository, private val properties: PropertyRepository,
    private val units: UnitService, private val listings: ListingService, private val sessions: UploadSessionRepository,
    private val storage: MediaStorage, private val validation: MediaValidation,
    private val managedModels: ManagedModelService, private val modelRecords: Room3DModelRepository, private val pipeline: Room3DModelService
) {
    @Autowired private lateinit var inspectedStorage: InspectableStorage
    @Autowired private lateinit var correctionOutbox: com.hanyang.backend.domain.model3d.outbox.OutboxEventRepository
    private var owner=0L; private var other=0L; private var unit=0L
    private val png get() = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",it) }.toByteArray()
    @BeforeEach fun setup() {
        val suffix=java.util.UUID.randomUUID().toString()
        owner=requireNotNull(users.saveAndFlush(User(email="media-owner-$suffix@example.com",name="Owner",provider="TEST",providerId="media-owner-$suffix",role="HOST")).id)
        other=requireNotNull(users.saveAndFlush(User(email="media-other-$suffix@example.com",name="Other",provider="TEST",providerId="media-other-$suffix",role="GUEST")).id)
        asUser(owner)
        val property=properties.saveAndFlush(Property(title="Media building",address="private",creatorUserId=owner))
        unit=units.create(UnitCreateRequest(requireNotNull(property.id),"101","SEOUL","서울")).id
    }
    @AfterEach fun clear() { inspectedStorage.beforeFinal = null; SecurityContextHolder.clearContext() }
    @Test fun `validated file is immutable after staging replacement and complete is idempotent`() {
        val original=png
        val s=media.create(UploadSessionRequest(unit,MediaKind.IMAGE,"image/png",original.size.toLong()))
        assertTrue(s.uploadUrl.startsWith("/api/v2/media/"))
        media.upload(s.id,"image/png",original.inputStream())
        val asset=media.complete(s.id)
        assertEquals(MediaStatus.VERIFIED,asset.status)
        assertEquals(asset.id,media.complete(s.id).id)
        val storedSession=sessions.findById(s.id).orElseThrow()
        storage.putStaging(storedSession.stagingKey,"tampered".toByteArray(),"image/png")
        assertArrayEquals(original,media.privateRead(asset.id).bytes)
        assertThrows(ApiException::class.java) { media.upload(s.id,"image/png",original.inputStream()) }
    }
    @Test fun `unowned sessions and oversized or falsely labelled files cannot complete`() {
        val original=png
        val s=media.create(UploadSessionRequest(unit,MediaKind.IMAGE,"image/png",original.size.toLong()))
        asUser(other)
        assertEquals(403,assertThrows(ApiException::class.java) { media.complete(s.id) }.status.value())
        assertEquals(403,assertThrows(ApiException::class.java) { media.create(UploadSessionRequest(unit,MediaKind.IMAGE,"image/png",1)) }.status.value())
        asUser(owner)
        assertThrows(ApiException::class.java) { media.upload(s.id,"image/png",ByteArray(original.size+1).inputStream()) }
        media.upload(s.id,"image/png",ByteArray(original.size).inputStream())
        assertEquals("INVALID_FILE",assertThrows(ApiException::class.java) { media.complete(s.id) }.code)
        assertEquals(null,sessions.findById(s.id).orElseThrow().assetId)
    }
    @Test fun `expired session and mismatched content type are rejected`() {
        val s=media.create(UploadSessionRequest(unit,MediaKind.IMAGE,"image/png",png.size.toLong()))
        assertThrows(ApiException::class.java) { media.upload(s.id,"image/jpeg",png.inputStream()) }
        sessions.saveAndFlush(UploadSession(unitId=unit,ownerUserId=owner,kind=MediaKind.IMAGE,stagingKey="staging/expired",contentType="image/png",sizeBytes=1,expiresAt=LocalDateTime.now().minusSeconds(1))).let {
            assertEquals(410,assertThrows(ApiException::class.java) { media.complete(it.id) }.status.value())
        }
    }
    @Test fun `public file is accessible only through a currently published referencing listing`() {
        val s=media.create(UploadSessionRequest(unit,MediaKind.IMAGE,"image/png",png.size.toLong()))
        media.upload(s.id,"image/png",png.inputStream()); val asset=media.complete(s.id)
        val draft=listings.create(unit,ListingCreateRequest("월세",depositWon=1000,monthlyRentWon=100,representativeAssetId=asset.id,photoAssetIds=listOf(asset.id)))
        assertEquals(404,assertThrows(ApiException::class.java) { media.publicRead(draft.id,asset.id) }.status.value())
        val published=listings.publish(draft.id,ListingVersionRequest(draft.version))
        assertArrayEquals(png,media.publicRead(draft.id,asset.id).bytes)
        asUser(other)
        assertThrows(ApiException::class.java) { media.privateRead(asset.id) }
        asUser(owner); listings.hide(draft.id,ListingVersionRequest(published.version))
        assertEquals(404,assertThrows(ApiException::class.java) { media.publicRead(draft.id,asset.id) }.status.value())
    }
    @Test fun `GLB header and external file references are checked`() {
        fun glb(json:String):ByteArray {
            val text=json.toByteArray(); val length=(text.size+3)/4*4
            return java.nio.ByteBuffer.allocate(20+length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546c67).putInt(2).putInt(20+length).putInt(length).putInt(0x4e4f534a)
                .put(text).put(ByteArray(length-text.size){32}).array()
        }
        validation.validate(MediaKind.MODEL,"model/gltf-binary",triangleGlb())
        assertThrows(ApiException::class.java) { validation.validate(MediaKind.MODEL,"model/gltf-binary",glb("""{"asset":{"version":"2.0"},"buffers":[{"uri":"https://remote.example/file"}]}""")) }
        assertThrows(ApiException::class.java) { validation.validate(MediaKind.MODEL,"model/gltf-binary",ByteArray(40)) }
        assertThrows(ApiException::class.java) { validation.validate(MediaKind.MODEL,"model/gltf-binary",glb(" " .repeat(1_048_580))) }
    }
    @Test fun `manual triangle GLB creates one space model and can be selected in a published listing`() {
        val photo=uploadPhoto()
        val space=units.createSpace(unit,SpaceCreateRequest("거실")).id
        val bytes=triangleGlb()
        val session=media.create(UploadSessionRequest(unit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong(),spaceId=space,sourceImageAssetIds=listOf(photo.id)))
        media.upload(session.id,"model/gltf-binary",bytes.inputStream())
        val result=media.complete(session.id)
        assertTrue(result.sourceModelId != null);assertEquals(space,result.spaceId)
        assertEquals(result,media.complete(session.id))
        val records=managedModels.list(space)
        assertEquals(1,records.size);assertEquals(ModelSourceType.MANUAL_UPLOAD,records.single().sourceType)
        assertEquals(listOf(photo.id),records.single().sourceImageAssetIds)
        val draft=listings.create(unit,ListingCreateRequest("3D 월세",depositWon=1000,monthlyRentWon=100,
            representativeAssetId=photo.id,photoAssetIds=listOf(photo.id),modelAssetId=result.id,selectedModelId=result.sourceModelId,spaceId=space))
        listings.publish(draft.id,ListingVersionRequest(draft.version))
        assertArrayEquals(bytes,media.publicRead(draft.id,result.id).bytes)
    }
    @Test fun `managed pipeline preserves explicit model identity through correction callback and verified upload`() {
        val photos=(1..4).map { uploadPhoto(pngWithMarker(it)) }
        val photo=photos.first();val photoIds=photos.map { it.id }
        val space=units.createSpace(unit,SpaceCreateRequest("침실")).id
        val first=managedModels.analyze(space,ManagedAnalyzeRequest(photoIds))
        val second=managedModels.analyze(space,ManagedAnalyzeRequest(photoIds))
        assertEquals(photoIds, first.sourceImageAssetIds)
        val model=modelRecords.findById(first.modelId).orElseThrow()
        assertEquals(ModelSourceType.AI_PIPELINE,model.sourceType)
        assertEquals("http://localhost:8080/api/internal/ai/assets/${photo.id}/content",model.originalImageUrl)
        pipeline.handleAiWebhook(AiWebhookRequest(model.aiRoomId,RenderStatus.WAITING_FOR_CORRECTION,"""{"aiVertices":[{"id":"a","x":0,"y":0,"z":0},{"id":"b","x":1,"y":0,"z":0}],"aiEdges":[{"id":"ab","startVertexId":"a","endVertexId":"b"}]}""",AiWebhookPhase.ANALYZE))
        managedModels.reconstruct(space,first.modelId,CorrectionRequestDto(listOf(VertexDto("aaaaaaaa-0000-0000-0000-000000000001",0.0,0.0,0.0,imageIndex=0)),emptyList()))
        assertEquals(ModelStatus.RECONSTRUCTING,managedModels.get(space,first.modelId).status)
        assertEquals(ModelStatus.ANALYZING,managedModels.get(space,second.modelId).status)
        pipeline.handleAiWebhook(AiWebhookRequest(model.aiRoomId,RenderStatus.SUCCESS,"""{"finalVertices":[{"id":"a","x":0,"y":0,"z":0},{"id":"b","x":1,"y":0,"z":0}],"finalEdges":[{"id":"ab","startVertexId":"a","endVertexId":"b"}]}""",AiWebhookPhase.RECONSTRUCT))
        val bytes=triangleGlb();val session=media.create(UploadSessionRequest(unit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong(),spaceId=space,modelId=first.modelId))
        media.upload(session.id,"model/gltf-binary",bytes.inputStream());val asset=media.complete(session.id)
        assertEquals(first.modelId,asset.sourceModelId)
        assertEquals(ModelStatus.SUCCESS,managedModels.get(space,first.modelId).status)
        assertEquals(asset.id,managedModels.get(space,first.modelId).assetId)
        pipeline.handleAiWebhook(AiWebhookRequest(model.aiRoomId,RenderStatus.FAILED,"""{"message":"late"}""",AiWebhookPhase.RECONSTRUCT))
        assertEquals(ModelStatus.SUCCESS,managedModels.get(space,first.modelId).status)
        assertEquals("MODEL_NOT_READY_FOR_UPLOAD",assertThrows(ApiException::class.java) {
            media.create(UploadSessionRequest(unit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong(),spaceId=space,modelId=first.modelId))
        }.code)
    }
    @Test fun `invalid photo corrections cannot change source identity or enqueue reconstruction`() {
        val (space, modelId, vertices) = photoCorrectionFixture()
        val before = managedModels.get(space, modelId)
        val commandsBefore = correctionOutbox.count()
        val invalidRequests = listOf(
            "INVALID_IMAGE_COORDINATES" to listOf(vertices[0].copy(z=1.0)),
            "INVALID_IMAGE_COORDINATES" to listOf(vertices[0].copy(x=-0.01)),
            "INVALID_IMAGE_COORDINATES" to listOf(vertices[0].copy(y=Double.NaN)),
            "IMAGE_COORDINATES_OUT_OF_BOUNDS" to listOf(vertices[0].copy(x=2.0)),
            "IMAGE_COORDINATES_OUT_OF_BOUNDS" to listOf(vertices[0].copy(y=2.0)),
            "SOURCE_VERTEX_MISMATCH" to listOf(vertices[0].copy(imageIndex=1)),
            "SOURCE_VERTEX_MISMATCH" to listOf(vertices[0].copy(sourceVertexId=null)),
            "SOURCE_VERTEX_MISMATCH" to listOf(vertices[0].copy(sourceVertexId="cccccccc-0000-0000-0000-000000000002")),
            "INVALID_SOURCE_VERTEX" to listOf(vertices[0].copy(id="dddddddd-0000-0000-0000-000000000001"))
        )
        invalidRequests.forEach { (code, points) ->
            assertEquals(code, assertThrows(ApiException::class.java) {
                managedModels.reconstruct(space, modelId, CorrectionRequestDto(points, emptyList()))
            }.code)
            assertEquals(before, managedModels.get(space, modelId))
        }
        assertEquals("CROSS_IMAGE_EDGE", assertThrows(ApiException::class.java) {
            managedModels.reconstruct(space, modelId, CorrectionRequestDto(vertices,
                listOf(EdgeDto("eeeeeeee-0000-0000-0000-000000000001", vertices[0].id, vertices[1].id))))
        }.code)
        assertEquals(before, managedModels.get(space, modelId))
        assertEquals(commandsBefore, correctionOutbox.count())
    }

    @Test fun `photo correction accepts subpixel edits and new unlinked points in the same image`() {
        val (space, modelId, vertices) = photoCorrectionFixture()
        val original = vertices[0].copy(x=1.5, y=1.75)
        val added = VertexDto("dddddddd-0000-0000-0000-000000000001",1.0,1.0,0.0,imageIndex=0)
        val result = managedModels.reconstruct(space, modelId, CorrectionRequestDto(listOf(original, added),
            listOf(EdgeDto("eeeeeeee-0000-0000-0000-000000000001", original.id, added.id))))
        assertEquals(ModelStatus.RECONSTRUCTING, result.status)
    }

    private fun photoCorrectionFixture(): Triple<Long, Long, List<VertexDto>> {
        val photos=(1..4).map { uploadPhoto(pngWithMarker(it)) }
        val space=units.createSpace(unit,SpaceCreateRequest("사진 보정 검증 공간")).id
        val created=managedModels.analyze(space,ManagedAnalyzeRequest(photos.map { it.id }))
        val model=modelRecords.findById(created.modelId).orElseThrow()
        val points=listOf(
            VertexDto("aaaaaaaa-0000-0000-0000-000000000001",1.0,1.0,0.0,0,"cccccccc-0000-0000-0000-000000000001"),
            VertexDto("bbbbbbbb-0000-0000-0000-000000000001",1.0,1.0,0.0,1,"cccccccc-0000-0000-0000-000000000001"),
            VertexDto("aaaaaaaa-0000-0000-0000-000000000002",1.5,1.0,0.0,0,"cccccccc-0000-0000-0000-000000000002")
        )
        val edges=listOf(EdgeDto("eeeeeeee-0000-0000-0000-000000000002",points[0].id,points[2].id))
        val payload=JsonMapper.builder().build().writeValueAsString(mapOf("aiVertices" to points,"aiEdges" to edges))
        pipeline.handleAiWebhook(AiWebhookRequest(model.aiRoomId,RenderStatus.WAITING_FOR_CORRECTION,payload,AiWebhookPhase.ANALYZE))
        assertEquals(ModelStatus.WAITING_FOR_CORRECTION,managedModels.get(space,created.modelId).status)
        return Triple(space,created.modelId,points)
    }

    @Test fun `analysis rejects incomplete repeated or duplicate-content photo sets before creating a model`() {
        val space=units.createSpace(unit,SpaceCreateRequest("4사진 검증 공간")).id
        val photos=(1..4).map { uploadPhoto(pngWithMarker(it)) }
        for (count in listOf(0,1,2,3,5)) {
            val ids=if(count==5) photos.map { it.id } + uploadPhoto(pngWithMarker(5)).id else photos.take(count).map { it.id }
            assertEquals("INVALID_SOURCE_IMAGE_COUNT",assertThrows(ApiException::class.java) {
                managedModels.analyze(space,ManagedAnalyzeRequest(ids))
            }.code)
        }
        assertEquals("INVALID_SOURCE_IMAGES",assertThrows(ApiException::class.java) {
            managedModels.analyze(space,ManagedAnalyzeRequest(List(4){photos.first().id}))
        }.code)
        val identical=(1..4).map { uploadPhoto(pngWithMarker(20)).id }
        assertEquals("DUPLICATE_SOURCE_IMAGES",assertThrows(ApiException::class.java) {
            managedModels.analyze(space,ManagedAnalyzeRequest(identical))
        }.code)
        assertTrue(managedModels.list(space).isEmpty())
        val model=managedModels.analyze(space,ManagedAnalyzeRequest(photos.map { it.id }.reversed()))
        assertEquals(photos.map { it.id }.reversed(),model.sourceImageAssetIds)
        assertEquals(1,managedModels.list(space).size)
    }

    @Test fun `foreign space and unrelated source images cannot be attached to uploaded models`() {
        val photo=uploadPhoto();val space=units.createSpace(unit,SpaceCreateRequest("거실")).id
        asUser(other)
        assertEquals(403,assertThrows(ApiException::class.java) { managedModels.list(space) }.status.value())
        asUser(owner)
        val anotherProperty=properties.saveAndFlush(Property(title="Other building",address="private",creatorUserId=owner))
        val secondUnit=units.create(UnitCreateRequest(requireNotNull(anotherProperty.id),"102","SEOUL","서울")).id
        val bytes=triangleGlb()
        assertThrows(ApiException::class.java) { media.create(UploadSessionRequest(secondUnit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong(),spaceId=space)) }
        val secondSpace=units.createSpace(secondUnit,SpaceCreateRequest("주방")).id
        assertEquals("INVALID_SOURCE_IMAGE",assertThrows(ApiException::class.java) {
            media.create(UploadSessionRequest(secondUnit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong(),spaceId=secondSpace,sourceImageAssetIds=listOf(photo.id)))
        }.code)
        assertEquals("MODEL_SPACE_REQUIRED",assertThrows(ApiException::class.java) { media.create(UploadSessionRequest(unit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong())) }.code)
    }
    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    fun `simultaneous completion commits a single model asset and upload session`() {
        val space=units.createSpace(unit,SpaceCreateRequest("동시 업로드 공간")).id
        val bytes=triangleGlb();val session=media.create(UploadSessionRequest(unit,MediaKind.MODEL,"model/gltf-binary",bytes.size.toLong(),spaceId=space))
        media.upload(session.id,"model/gltf-binary",bytes.inputStream())
        val barrier=java.util.concurrent.CyclicBarrier(2)
        inspectedStorage.beforeFinal = { barrier.await(10,java.util.concurrent.TimeUnit.SECONDS); Unit }
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map { executor.submit<MediaAssetView> {
                asUser(owner)
                try { media.complete(session.id) } finally { SecurityContextHolder.clearContext() }
            } }.map { it.get(15,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(results[0].id,results[1].id)
            assertEquals(results[0].sourceModelId,results[1].sourceModelId)
            assertEquals(1,managedModels.list(space).size)
            assertEquals(results[0].id,sessions.findById(session.id).orElseThrow().assetId)
            assertArrayEquals(bytes,media.privateRead(results[0].id).bytes)
        } finally { executor.shutdownNow() }
    }
    private fun pngWithMarker(marker: Int): ByteArray = ByteArrayOutputStream().also {
        val image = BufferedImage(2,2,BufferedImage.TYPE_INT_RGB)
        image.setRGB(0,0,marker)
        ImageIO.write(image,"png",it)
    }.toByteArray()
    private fun uploadPhoto(bytes: ByteArray = png):MediaAssetView {
        val session=media.create(UploadSessionRequest(unit,MediaKind.IMAGE,"image/png",bytes.size.toLong()))
        media.upload(session.id,"image/png",bytes.inputStream());return media.complete(session.id)
    }
    // Embedded indexed triangle with real positions, indices and a scene, also usable by a browser GLTFLoader.
    private fun triangleGlb():ByteArray {
        val json="""{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"buffers":[{"byteLength":42}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},{"buffer":0,"byteOffset":36,"byteLength":6,"target":34963}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR","min":[0],"max":[2]}]}""".toByteArray()
        val jsonSize=(json.size+3)/4*4;val binSize=44;val total=12+8+jsonSize+8+binSize
        val buffer=java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546c67).putInt(2).putInt(total).putInt(jsonSize).putInt(0x4e4f534a).put(json).put(ByteArray(jsonSize-json.size){32})
        buffer.putInt(binSize).putInt(0x004e4942)
        floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f).forEach { buffer.putFloat(it) }
        buffer.putShort(0).putShort(1).putShort(2).putShort(0)
        return buffer.array()
    }
    private fun asUser(id:Long) { SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(AuthenticatedUser(id),null,emptyList()) }
    class InspectableStorage(private val delegate:MediaStorage):MediaStorage by delegate {
        @Volatile var beforeFinal:(() -> Unit)? = null
        override fun putFinal(key:String,bytes:ByteArray,contentType:String):StoredObject {
            beforeFinal?.invoke()
            return delegate.putFinal(key,bytes,contentType)
        }
    }
    @TestConfiguration class JsonConfig {
        @Bean @Primary fun inspectableStorage(@Qualifier("mediaStorage") storage:MediaStorage)=InspectableStorage(storage)
        @Bean fun jsonMapper():JsonMapper=JsonMapper.builder().build()
        @Bean @ConditionalOnMissingBean(TransactionOperations::class)
        fun transactionOperations(manager:PlatformTransactionManager):TransactionOperations=TransactionTemplate(manager)
    }
}
