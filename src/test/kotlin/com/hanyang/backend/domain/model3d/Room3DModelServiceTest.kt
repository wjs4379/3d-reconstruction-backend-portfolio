package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.model3d.outbox.OutboxEventRepository
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.room.AiWebhookPhase
import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.room.RenderStatus
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.Optional

class Room3DModelServiceTest {

    private lateinit var roomRepository: RoomRepository
    private lateinit var room3DModelRepository: Room3DModelRepository
    private lateinit var outboxEventRepository: OutboxEventRepository
    private lateinit var aiServerClient: AiServerClient
    private lateinit var jsonMapper: JsonMapper
    private lateinit var service: Room3DModelService

    private val aiRoomId = "test-ai-room-id"

    @BeforeEach
    fun setUp() {
        roomRepository = Mockito.mock(RoomRepository::class.java)
        room3DModelRepository = Mockito.mock(Room3DModelRepository::class.java)
        outboxEventRepository = Mockito.mock(OutboxEventRepository::class.java)
        aiServerClient = Mockito.mock(AiServerClient::class.java)
        jsonMapper = JsonMapper.builder().build()

        service = Room3DModelService(
            roomRepository = roomRepository,
            room3DModelRepository = room3DModelRepository,
            outboxEventRepository = outboxEventRepository,
            aiServerClient = aiServerClient,
            jsonMapper = jsonMapper,
            transactionOperations = TransactionOperations.withoutTransaction(),
            outboxEnabled = false,
            webhookUrl = "http://localhost:8080/api/webhook"
        )
    }

    @Test
    fun `분석 중에 보정 대기 웹훅을 받으면 WAITING_FOR_CORRECTION으로 변경된다`() {
        val model = createModel(ModelStatus.ANALYZING)
        val roomData = wireJson("ai")

        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.WAITING_FOR_CORRECTION,
            roomDataJson = roomData
        )

        service.handleAiWebhook(request)

        assertEquals(ModelStatus.WAITING_FOR_CORRECTION, model.status)
        assertEquals(roomData, model.roomData)
    }

    @Test
    fun `AI 최종 좌표 웹훅을 받으면 프론트 모델 업로드 대기 상태로 변경된다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        val roomData = wireJson("final")

        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            roomDataJson = roomData
        )

        service.handleAiWebhook(request)

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals(roomData, model.roomData)
    }

    @Test
    fun `프론트가 최종 모델 URL을 전달하면 SUCCESS로 변경된다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)
        model.roomData = wireJson("final")

        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        val response = service.completeModelUpload(
            roomId = 1L,
            modelId = 10L,
            request = ModelUploadResultRequest(
                modelUrl = "https://example.com/model.glb",
                fileSizeMb = BigDecimal("12.34"),
                format = "glb"
            )
        )

        assertEquals(ModelStatus.SUCCESS, model.status)
        assertEquals("https://example.com/model.glb", model.modelUrl)
        assertEquals(BigDecimal("12.34"), model.fileSizeMb)
        assertEquals("glb", model.format)
        assertEquals(ModelStatus.SUCCESS, response.status)
        assertEquals("https://example.com/model.glb", response.modelUrl)
        assertEquals(wireJson("final"), model.roomData)
    }

    @Test
    fun `AI 최종 좌표가 오기 전에는 프론트 모델 URL을 저장할 수 없다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)

        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalStateException::class.java) {
            service.completeModelUpload(
                roomId = 1L,
                modelId = 10L,
                request = ModelUploadResultRequest("https://example.com/model.glb")
            )
        }
    }

    @Test
    fun `유효하지 않은 최종 모델 URL은 저장할 수 없다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)

        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalArgumentException::class.java) {
            service.completeModelUpload(
                roomId = 1L,
                modelId = 10L,
                request = ModelUploadResultRequest("blob:temporary-model")
            )
        }
    }

    @Test
    fun `roomId와 modelId 조합이 다르면 최종 모델 URL을 저장할 수 없다`() {
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.empty())

        assertThrows(IllegalArgumentException::class.java) {
            service.completeModelUpload(
                roomId = 1L,
                modelId = 10L,
                request = ModelUploadResultRequest("https://example.com/model.glb")
            )
        }
    }

    @Test
    fun `프론트 업로드 대기 상태는 AI 상태 재조회로 역행하지 않는다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)

        Mockito.`when`(room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId))
            .thenReturn(
                AiRoomResponseDto(
                    id = aiRoomId,
                    status = "reconstructing"
                )
            )

        val response = service.refreshFromAi(1L)

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, response.status)
    }

    @Test
    fun `AI 최종 Webhook이 유실돼도 상태 재조회로 최종 좌표를 복구한다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        val finalVertex = VertexDto(
            id = "final-vertex",
            x = 1.0,
            y = 2.0,
            z = 3.0
        )

        Mockito.`when`(room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId))
            .thenReturn(
                AiRoomResponseDto(
                    id = aiRoomId,
                    status = "completed",
                    finalVertices = wireVertices(finalVertex),
                    finalEdges = wireEdges(finalVertex.id)
                )
            )

        val response = service.refreshFromAi(1L)
        val roomData = response.roomData as Map<*, *>

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals(2, (roomData["finalVertices"] as List<*>).size)
        assertEquals(1, (roomData["finalEdges"] as List<*>).size)
    }

    @Test
    fun `AI 1차 Webhook이 유실돼도 상태 재조회로 보정 좌표를 복구한다`() {
        val model = createModel(ModelStatus.ANALYZING)
        val analyzedVertex = VertexDto(
            id = "analyzed-vertex",
            x = 1.0,
            y = 2.0,
            z = 3.0,
            imageIndex = 0
        )
        Mockito.`when`(room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId))
            .thenReturn(
                AiRoomResponseDto(
                    id = aiRoomId,
                    status = "waiting_for_correction",
                    aiVertices = wireVertices(analyzedVertex),
                    aiEdges = wireEdges(analyzedVertex.id)
                )
            )

        val response = service.refreshFromAi(1L)
        val roomData = response.roomData as Map<*, *>
        val vertex = (roomData["aiVertices"] as List<*>).first() as Map<*, *>

        assertEquals(ModelStatus.WAITING_FOR_CORRECTION, response.status)
        assertEquals("analyzed-vertex", vertex["id"])
        assertEquals(1, (roomData["aiEdges"] as List<*>).size)
    }

    @Test
    fun `AI 상태 재조회에서도 잘못 연결된 최종 edge는 완료 좌표로 반영하지 않는다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        Mockito.`when`(room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId))
            .thenReturn(
                AiRoomResponseDto(
                    id = aiRoomId,
                    status = "completed",
                    finalVertices = listOf(VertexDto("vertex-1", 1.0, 2.0, 3.0)),
                    finalEdges = listOf(
                        EdgeDto(
                            id = "edge-1",
                            startVertexId = "vertex-1",
                            endVertexId = "missing-vertex"
                        )
                    )
                )
            )

        val response = service.refreshFromAi(1L)

        assertEquals(ModelStatus.RECONSTRUCTING, response.status)
        assertEquals(null, response.roomData)
    }

    @Test
    fun `업로드 대기 이후 늦은 실패 Webhook은 최종 좌표를 덮어쓰지 않는다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)
        model.roomData = """{"finalVertices":[{"id":"final"}]}"""

        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        service.handleAiWebhook(
            AiWebhookRequest(
                aiRoomId = aiRoomId,
                status = RenderStatus.FAILED,
                roomDataJson = """{"code":"LATE_FAILURE"}"""
            )
        )

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals("""{"finalVertices":[{"id":"final"}]}""", model.roomData)
    }

    @Test
    fun `같은 모델 URL을 다시 전달하면 멱등하게 성공 상태를 반환한다`() {
        val model = createModel(ModelStatus.SUCCESS)
        model.modelUrl = "https://example.com/model.glb"

        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        val response = service.completeModelUpload(
            roomId = 1L,
            modelId = 10L,
            request = ModelUploadResultRequest("https://example.com/model.glb")
        )

        assertEquals(ModelStatus.SUCCESS, response.status)
        assertEquals("https://example.com/model.glb", response.modelUrl)
    }

    @Test
    fun `이미 완료된 모델의 URL은 다른 값으로 덮어쓸 수 없다`() {
        val model = createModel(ModelStatus.SUCCESS)
        model.modelUrl = "https://example.com/final.glb"

        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalStateException::class.java) {
            service.completeModelUpload(
                roomId = 1L,
                modelId = 10L,
                request = ModelUploadResultRequest("https://example.com/other.glb")
            )
        }
    }

    @Test
    fun `이미 성공한 모델에 늦은 보정 대기 웹훅이 와도 상태와 결과를 유지한다`() {
        val model = createModel(ModelStatus.SUCCESS)
        model.roomData = """{"finalVertices":[{"id":"final"}]}"""
        val finalRoomData = model.roomData

        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.WAITING_FOR_CORRECTION,
            roomDataJson = """{"aiVertices":[],"aiEdges":[]}"""
        )

        service.handleAiWebhook(request)

        assertEquals(ModelStatus.SUCCESS, model.status)
        assertEquals(finalRoomData, model.roomData)
    }

    @Test
    fun `이미 성공한 모델은 후속 성공 웹훅으로 결과를 덮어쓰지 않는다`() {
        val model = createModel(ModelStatus.SUCCESS)
        model.modelUrl = "https://example.com/final.glb"
        model.roomData = """{"version":"final"}"""

        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            roomDataJson = """{"version":"stale"}"""
        )

        service.handleAiWebhook(request)

        assertEquals("https://example.com/final.glb", model.modelUrl)
        assertEquals("""{"version":"final"}""", model.roomData)
    }

    @Test
    fun `존재하지 않는 aiRoomId이면 예외가 발생한다`() {
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.empty())

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            roomDataJson = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.handleAiWebhook(request)
        }

        assertEquals(
            "해당 AI Room ID를 찾을 수 없습니다. aiRoomId=$aiRoomId",
            exception.message
        )
    }

    @Test
    fun `재구성 SUCCESS 웹훅에 최종 좌표가 없으면 상태를 완료 단계로 바꾸지 않는다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.handleAiWebhook(
                AiWebhookRequest(
                    aiRoomId = aiRoomId,
                    status = RenderStatus.SUCCESS,
                    roomDataJson = null
                )
            )
        }

        assertEquals(
            "AI 최종 SUCCESS Webhook에는 finalVertices와 finalEdges가 필요합니다.",
            exception.message
        )
        assertEquals(ModelStatus.RECONSTRUCTING, model.status)
        assertEquals(null, model.roomData)
    }

    @Test
    fun `분석 완료 웹훅에 좌표 데이터가 없으면 보정 대기로 바꾸지 않는다`() {
        val model = createModel(ModelStatus.ANALYZING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalArgumentException::class.java) {
            service.handleAiWebhook(
                AiWebhookRequest(
                    aiRoomId = aiRoomId,
                    status = RenderStatus.WAITING_FOR_CORRECTION,
                    roomDataJson = null
                )
            )
        }

        assertEquals(ModelStatus.ANALYZING, model.status)
        assertEquals(null, model.roomData)
    }

    @Test
    fun `분석 완료 웹훅의 좌표 객체가 비어 있으면 보정 대기로 바꾸지 않는다`() {
        val model = createModel(ModelStatus.ANALYZING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalArgumentException::class.java) {
            service.handleAiWebhook(
                AiWebhookRequest(
                    aiRoomId = aiRoomId,
                    status = RenderStatus.WAITING_FOR_CORRECTION,
                    roomDataJson = "{}"
                )
            )
        }

        assertEquals(ModelStatus.ANALYZING, model.status)
        assertEquals(null, model.roomData)
    }

    @Test
    fun `유효하지 않은 roomData JSON 웹훅은 저장하지 않는다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalArgumentException::class.java) {
            service.handleAiWebhook(
                AiWebhookRequest(
                    aiRoomId = aiRoomId,
                    status = RenderStatus.SUCCESS,
                    roomDataJson = "not-json"
                )
            )
        }

        assertEquals(ModelStatus.RECONSTRUCTING, model.status)
        assertEquals(null, model.roomData)
    }

    @Test
    fun `최종 좌표 배열의 원소 구조가 잘못되면 완료 단계로 바꾸지 않는다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalArgumentException::class.java) {
            service.handleAiWebhook(
                AiWebhookRequest(
                    aiRoomId = aiRoomId,
                    status = RenderStatus.SUCCESS,
                    roomDataJson =
                        """{"finalVertices":["invalid"],"finalEdges":[]}"""
                )
            )
        }

        assertEquals(ModelStatus.RECONSTRUCTING, model.status)
        assertEquals(null, model.roomData)
    }

    @Test
    fun `실패한 작업은 늦은 재구성 웹훅으로 되살아나지 않는다`() {
        val model = createModel(ModelStatus.FAILED)
        model.roomData = """{"code":"AI_REQUEST_FAILED"}"""
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        service.handleAiWebhook(
            AiWebhookRequest(
                aiRoomId = aiRoomId,
                status = RenderStatus.RECONSTRUCTING,
                roomDataJson = """{"version":"late"}"""
            )
        )

        assertEquals(ModelStatus.FAILED, model.status)
        assertEquals("""{"code":"AI_REQUEST_FAILED"}""", model.roomData)
    }

    @Test
    fun `실패 웹훅의 결과 문자열이 JSON이 아니어도 실패 상태는 반영한다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId))
            .thenReturn(Optional.of(model))

        service.handleAiWebhook(
            AiWebhookRequest(
                aiRoomId = aiRoomId,
                status = RenderStatus.FAILED,
                roomDataJson = "AI reconstruction failed"
            )
        )

        assertEquals(ModelStatus.FAILED, model.status)
        assertEquals(null, model.roomData)
    }

    @Test
    fun `업로드 대기 상태라도 최종 좌표가 없으면 모델 URL을 저장할 수 없다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)
        model.roomData = """{"aiVertices":[],"aiEdges":[]}"""
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalStateException::class.java) {
            service.completeModelUpload(
                roomId = 1L,
                modelId = 10L,
                request = ModelUploadResultRequest("https://example.com/model.glb")
            )
        }

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals(null, model.modelUrl)
    }

    @Test
    fun `업로드 대기 중 좌표가 비어 있으면 AI 상태 재조회로 복구한다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)
        val finalVertex = VertexDto(
            id = "final-vertex",
            x = 1.0,
            y = 2.0,
            z = 3.0,
            sourceVertexId = "source-vertex"
        )
        Mockito.`when`(room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId))
            .thenReturn(
                AiRoomResponseDto(
                    id = aiRoomId,
                    status = "completed",
                    finalVertices = wireVertices(finalVertex),
                    finalEdges = wireEdges(finalVertex.id)
                )
            )

        val response = service.refreshFromAi(1L)
        val roomData = response.roomData as Map<*, *>
        val vertex = (roomData["finalVertices"] as List<*>).first() as Map<*, *>

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, response.status)
        assertEquals("final-vertex", vertex["id"])
        assertEquals("source-vertex", vertex["sourceVertexId"])
    }

    @Test
    fun `업로드 대기 중 저장된 최종 좌표는 늦은 AI 상태 재조회로 덮어쓰지 않는다`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD)
        model.roomData = wireJson("final", "kept")
        Mockito.`when`(room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId))
            .thenReturn(
                AiRoomResponseDto(
                    id = aiRoomId,
                    status = "completed",
                    finalVertices = wireVertices(VertexDto("late", 9.0, 9.0, 9.0)),
                    finalEdges = wireEdges("late")
                )
            )

        val response = service.refreshFromAi(1L)
        val roomData = response.roomData as Map<*, *>
        val vertex = (roomData["finalVertices"] as List<*>).first() as Map<*, *>

        assertEquals("kept", vertex["id"])
    }

    @Test
    fun `완료 요청 재시도에서 같은 URL이라도 다른 메타데이터는 거부한다`() {
        val model = createModel(ModelStatus.SUCCESS)
        model.modelUrl = "https://example.com/model.glb"
        model.fileSizeMb = BigDecimal("12.34")
        model.format = "glb"
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        assertThrows(IllegalStateException::class.java) {
            service.completeModelUpload(
                roomId = 1L,
                modelId = 10L,
                request = ModelUploadResultRequest(
                    modelUrl = "https://example.com/model.glb",
                    fileSizeMb = BigDecimal("99.99"),
                    format = "glb"
                )
            )
        }

        assertEquals(BigDecimal("12.34"), model.fileSizeMb)
    }

    @Test
    fun `존재하지 않는 vertex를 가리키는 보정 edge는 AI로 보내지 않는다`() {
        val request = CorrectionRequestDto(
            vertices = listOf(VertexDto("vertex-1", 1.0, 2.0, 3.0)),
            edges = listOf(
                EdgeDto(
                    id = "edge-1",
                    startVertexId = "vertex-1",
                    endVertexId = "missing-vertex"
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.applyCorrectionAndReconstruct(1L, request)
        }

        Mockito.verifyNoInteractions(room3DModelRepository, aiServerClient)
    }

    @Test
    fun `managed callback without a durable phase cannot advance state or overwrite coordinates`() {
        val model = createModel(ModelStatus.ANALYZING).apply { sourceType = ModelSourceType.AI_PIPELINE }
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS, """{"aiVertices":[],"aiEdges":[]}"""))
        assertEquals(ModelStatus.ANALYZING, model.status)
        assertEquals(null, model.roomData)
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS, wireJson("ai"), AiWebhookPhase.ANALYZE))
        assertEquals(ModelStatus.WAITING_FOR_CORRECTION, model.status)
    }

    @Test
    fun `late analysis failure cannot fail reconstruction but reconstruction failure can`() {
        val original = """{"aiVertices":[],"aiEdges":[]}"""
        val model = createModel(ModelStatus.RECONSTRUCTING).apply { sourceType = ModelSourceType.AI_PIPELINE; roomData = original }
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.FAILED, """{"message":"old analysis"}""", AiWebhookPhase.ANALYZE))
        assertEquals(ModelStatus.RECONSTRUCTING, model.status)
        assertEquals(original, model.roomData)
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.FAILED, """{"message":"current reconstruction"}""", AiWebhookPhase.RECONSTRUCT))
        assertEquals(ModelStatus.FAILED, model.status)
        assertEquals("""{"message":"current reconstruction"}""", model.roomData)
    }

    @Test
    fun `analysis callback cannot start reconstruction before the persisted correction command`() {
        val model = createModel(ModelStatus.WAITING_FOR_CORRECTION).apply { sourceType = ModelSourceType.AI_PIPELINE }
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.RECONSTRUCTING, null, AiWebhookPhase.ANALYZE))
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.RECONSTRUCTING, null, AiWebhookPhase.RECONSTRUCT))
        assertEquals(ModelStatus.WAITING_FOR_CORRECTION, model.status)
    }

    @Test
    fun `refresh preserves native final coordinates with independent viewer up metadata`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        Mockito.`when`(room3DModelRepository.findById(10L)).thenReturn(Optional.of(model))
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.of(model))
        Mockito.`when`(aiServerClient.getRoom(aiRoomId)).thenReturn(AiRoomResponseDto(
            id = aiRoomId, status = "completed",
            finalVertices = wireVertices(VertexDto("native", 4.0, 3.0, 2.5)),
            finalEdges = wireEdges("native"), viewerUp = listOf(0.0, 0.0, 1.0)
        ))
        val response = service.refreshFromAi(1L, 10L)
        val data = response.roomData as Map<*, *>
        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, response.status)
        assertEquals(listOf(0.0, 0.0, 1.0), data["viewerUp"])
        val point = (data["finalVertices"] as List<*>).first() as Map<*, *>
        assertEquals(4.0, point["x"]); assertEquals(3.0, point["y"]); assertEquals(2.5, point["z"])
    }

    @Test
    fun `invalid callback viewer up cannot commit reconstruction state or coordinates`() {
        val model = createModel(ModelStatus.RECONSTRUCTING).apply { sourceType = ModelSourceType.AI_PIPELINE }
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
        for (up in listOf("[0,0,0]", "[0,1]", """[0,"up",1]""")) {
            assertThrows(IllegalArgumentException::class.java) {
                service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS,
                    """{"finalVertices":[],"finalEdges":[],"viewerUp":$up}""", AiWebhookPhase.RECONSTRUCT))
            }
            assertEquals(ModelStatus.RECONSTRUCTING, model.status)
            assertEquals(null, model.roomData)
        }
        val valid = wireJson("final").dropLast(1) + ""","viewerUp":[0,0,1]}"""
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS, valid, AiWebhookPhase.RECONSTRUCT))
        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals(valid, model.roomData)
    }

    @Test
    fun `empty analysis geometry cannot advance either managed or legacy callback jobs`() {
        for (sourceType in listOf(ModelSourceType.LEGACY_AI, ModelSourceType.AI_PIPELINE)) {
            for (status in listOf(RenderStatus.WAITING_FOR_CORRECTION, RenderStatus.SUCCESS)) {
                val model = createModel(ModelStatus.ANALYZING).apply { this.sourceType = sourceType }
                Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
                assertThrows(IllegalArgumentException::class.java) {
                    service.handleAiWebhook(AiWebhookRequest(aiRoomId, status,
                        """{"aiVertices":[],"aiEdges":[]}""", AiWebhookPhase.ANALYZE))
                }
                assertEquals(ModelStatus.ANALYZING, model.status)
                assertEquals(null, model.roomData)
            }
        }
    }

    @Test
    fun `empty point-only self-loop and collapsed final callback results never become upload ready`() {
        val vertices = wireVertices()
        val invalidResults = listOf(
            emptyList<VertexDto>() to emptyList<EdgeDto>(),
            vertices to emptyList(),
            vertices to listOf(EdgeDto("self", "start", "start")),
            listOf(vertices.first(), vertices.last().copy(x = vertices.first().x)) to wireEdges()
        )
        for ((points, edges) in invalidResults) {
            val original = wireJson("ai")
            val model = createModel(ModelStatus.RECONSTRUCTING).apply {
                sourceType = ModelSourceType.AI_PIPELINE
                roomData = original
            }
            Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
            val payload = jsonMapper.writeValueAsString(mapOf("finalVertices" to points, "finalEdges" to edges))
            assertThrows(IllegalArgumentException::class.java) {
                service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS, payload, AiWebhookPhase.RECONSTRUCT))
            }
            assertEquals(ModelStatus.RECONSTRUCTING, model.status)
            assertEquals(original, model.roomData)
            assertEquals(null, model.modelUrl)
        }
    }

    @Test
    fun `invalid result fields in progress callback cannot overwrite stored geometry`() {
        for ((state, phase) in listOf(
            ModelStatus.ANALYZING to AiWebhookPhase.ANALYZE,
            ModelStatus.RECONSTRUCTING to AiWebhookPhase.RECONSTRUCT
        )) {
            val status = if (state == ModelStatus.ANALYZING) RenderStatus.ANALYZING else RenderStatus.RECONSTRUCTING
            val prefix = if (state == ModelStatus.ANALYZING) "ai" else "final"
            val original = wireJson("ai")
            val model = createModel(state).apply {
                sourceType = ModelSourceType.AI_PIPELINE
                roomData = original
            }
            Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
            assertThrows(IllegalArgumentException::class.java) {
                service.handleAiWebhook(AiWebhookRequest(aiRoomId, status,
                    """{"${prefix}Vertices":[],"${prefix}Edges":[]}""", phase))
            }
            assertEquals(state, model.status)
            assertEquals(original, model.roomData)
        }
    }

    @Test
    fun `polling empty or collapsed AI results preserves phase and previous payload`() {
        for (state in listOf(ModelStatus.ANALYZING, ModelStatus.RECONSTRUCTING)) {
            for ((points, edges) in listOf(
                emptyList<VertexDto>() to emptyList<EdgeDto>(),
                wireVertices() to emptyList(),
                wireVertices().map { it.copy(x = 1.0) } to wireEdges()
            )) {
                val original = """{"progress":"running"}"""
                val model = createModel(state).apply { roomData = original }
                Mockito.`when`(room3DModelRepository.findById(10L)).thenReturn(Optional.of(model))
                Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.of(model))
                Mockito.`when`(aiServerClient.getRoom(aiRoomId)).thenReturn(AiRoomResponseDto(
                    id = aiRoomId,
                    status = if (state == ModelStatus.ANALYZING) "pending_correction" else "completed",
                    aiVertices = points, aiEdges = edges, finalVertices = points, finalEdges = edges
                ))
                val result = service.refreshFromAi(1L, 10L)
                assertEquals(state, result.status)
                assertEquals(original, model.roomData)
                assertEquals(null, model.modelUrl)
            }
        }
    }

    @Test
    fun `verified upload cannot complete a preexisting empty final result`() {
        val model = createModel(ModelStatus.WAITING_FOR_MODEL_UPLOAD).apply {
            sourceType = ModelSourceType.AI_PIPELINE
            roomData = """{"finalVertices":[],"finalEdges":[]}"""
        }
        Mockito.`when`(room3DModelRepository.findByIdAndRoomId(10L, 1L)).thenReturn(Optional.of(model))
        assertThrows(IllegalStateException::class.java) {
            service.completeVerifiedModelUpload(1L, 10L, 50L, 1520L)
        }
        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, model.status)
        assertEquals(null, model.modelUrl)
    }

    @Test
    fun `legacy generic coordinate names remain accepted for a usable analysis result`() {
        val model = createModel(ModelStatus.ANALYZING)
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
        val payload = jsonMapper.writeValueAsString(mapOf("vertices" to wireVertices(), "edges" to wireEdges()))
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS, payload))
        assertEquals(ModelStatus.WAITING_FOR_CORRECTION, model.status)
        assertEquals(payload, model.roomData)
    }

    @Test
    fun `wrong phase empty success callback cannot invalidate a running reconstruction`() {
        val original = wireJson("ai")
        val model = createModel(ModelStatus.RECONSTRUCTING).apply {
            sourceType = ModelSourceType.AI_PIPELINE
            roomData = original
        }
        Mockito.`when`(room3DModelRepository.findByAiRoomIdForUpdate(aiRoomId)).thenReturn(Optional.of(model))
        service.handleAiWebhook(AiWebhookRequest(aiRoomId, RenderStatus.SUCCESS,
            """{"aiVertices":[],"aiEdges":[]}""", AiWebhookPhase.ANALYZE))
        assertEquals(ModelStatus.RECONSTRUCTING, model.status)
        assertEquals(original, model.roomData)
    }

    private fun wireVertices(first: VertexDto = VertexDto("start", 1.0, 2.0, 3.0)) = listOf(
        first, VertexDto("${first.id}-end", first.x + 1.0, first.y, first.z, imageIndex = first.imageIndex)
    )

    private fun wireEdges(firstId: String = "start") = listOf(EdgeDto("edge", firstId, "$firstId-end"))

    private fun wireJson(prefix: String, firstId: String = "start") = jsonMapper.writeValueAsString(mapOf(
        "${prefix}Vertices" to wireVertices(VertexDto(firstId, 1.0, 2.0, 3.0)),
        "${prefix}Edges" to wireEdges(firstId)
    ))

    private fun createModel(status: ModelStatus): Room3DModel {
        val property = Property(
            id = 1L,
            title = "테스트 숙소",
            description = null,
            address = "서울"
        )
        val owner = User(
            id = 1L,
            email = "owner@example.com",
            name = "테스트 호스트",
            provider = "GOOGLE",
            providerId = "provider-id",
            role = "HOST"
        )
        val room = Room(
            id = 1L,
            property = property,
            owner = owner,
            roomName = "테스트 방",
            pricePerNight = 100_000,
            maxGuests = 2
        )

        return Room3DModel(
            id = 10L,
            room = room,
            aiRoomId = aiRoomId,
            originalImageUrl = "https://example.com/room.jpg",
            status = status
        )
    }
}
