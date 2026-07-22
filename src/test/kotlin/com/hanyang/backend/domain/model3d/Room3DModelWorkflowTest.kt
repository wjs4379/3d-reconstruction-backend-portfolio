package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.room.RenderStatus
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionOperations
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.Optional

class Room3DModelWorkflowTest {

    private lateinit var roomRepository: RoomRepository
    private lateinit var modelRepository: Room3DModelRepository
    private lateinit var aiClient: RecordingAiServerClient
    private lateinit var service: Room3DModelService

    @BeforeEach
    fun setUp() {
        roomRepository = Mockito.mock(RoomRepository::class.java)
        modelRepository = Mockito.mock(Room3DModelRepository::class.java)
        aiClient = RecordingAiServerClient()
        service = Room3DModelService(
            roomRepository = roomRepository,
            room3DModelRepository = modelRepository,
            aiServerClient = aiClient,
            jsonMapper = JsonMapper.builder().build(),
            transactionOperations = TransactionOperations.withoutTransaction(),
            webhookUrl = "http://localhost:8080/api/webhook"
        )
    }

    @Test
    fun `분석 요청의 모든 이미지 URL을 AI 서버에 전달한다`() {
        val room = createRoom()
        val imageUrls = listOf(
            "https://example.com/room-1.jpg",
            "https://example.com/room-2.jpg",
            "https://example.com/room-3.jpg"
        )

        Mockito.`when`(roomRepository.findById(1L))
            .thenReturn(Optional.of(room))
        Mockito.`when`(modelRepository.save(any(Room3DModel::class.java)))
            .thenAnswer { invocation -> invocation.getArgument(0) }

        val response = service.analyzeRoom(
            roomId = 1L,
            request = AnalyzeModelRequest(imageUrls)
        )

        assertEquals(ModelStatus.ANALYZING, response.status)
        assertEquals(imageUrls, aiClient.analyzeRequest?.imagePaths)
        assertEquals("테스트 방", aiClient.analyzeRequest?.roomName)
        assertNotNull(aiClient.analyzeRequest?.userId)
    }

    @Test
    fun `보정 처리 완료 후 재구성 요청을 순서대로 실행한다`() {
        val room = createRoom()
        val model = Room3DModel(
            room = room,
            aiRoomId = "ai-room-id",
            originalImageUrl = "https://example.com/room.jpg",
            status = ModelStatus.WAITING_FOR_CORRECTION
        )
        val correction = CorrectionRequestDto(
            vertices = listOf(
                VertexDto(
                    id = "vertex-1",
                    x = 1.0,
                    y = 2.0,
                    z = 3.0,
                    imageIndex = 0
                )
            ),
            edges = emptyList()
        )

        Mockito.`when`(modelRepository.findFirstByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))

        val response = service.applyCorrectionAndReconstruct(1L, correction)

        assertEquals(ModelStatus.RECONSTRUCTING, response.status)
        assertEquals(listOf("correction", "reconstruct"), aiClient.executionOrder)
    }

    @Test
    fun `AI 최종 좌표 완료 후 프론트 모델 업로드 결과로 최종 성공한다`() {
        val room = createRoom()
        val model = Room3DModel(
            id = 10L,
            room = room,
            aiRoomId = "ai-room-id",
            originalImageUrl = "https://example.com/room.jpg",
            status = ModelStatus.WAITING_FOR_CORRECTION
        )
        val correction = CorrectionRequestDto(
            vertices = listOf(
                VertexDto(
                    id = "vertex-1",
                    x = 1.0,
                    y = 2.0,
                    z = 3.0,
                    imageIndex = 0
                )
            ),
            edges = emptyList()
        )
        val finalRoomData =
            """{"finalVertices":[{"id":"vertex-1","x":1.0,"y":2.0,"z":3.0}],"finalEdges":[]}"""

        Mockito.`when`(modelRepository.findFirstByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(modelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))
        Mockito.`when`(modelRepository.findByAiRoomIdForUpdate("ai-room-id"))
            .thenReturn(Optional.of(model))
        Mockito.`when`(modelRepository.findByIdAndRoomId(10L, 1L))
            .thenReturn(Optional.of(model))

        val reconstructing = service.applyCorrectionAndReconstruct(1L, correction)

        assertEquals(ModelStatus.RECONSTRUCTING, reconstructing.status)

        service.handleAiWebhook(
            AiWebhookRequest(
                aiRoomId = "ai-room-id",
                status = RenderStatus.SUCCESS,
                roomDataJson = finalRoomData
            )
        )

        val waitingForUpload = service.getModelStatus(1L)
        val waitingRoomData = waitingForUpload.roomData as Map<*, *>

        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, waitingForUpload.status)
        assertEquals(null, waitingForUpload.modelUrl)
        assertEquals(1, (waitingRoomData["finalVertices"] as List<*>).size)
        assertEquals(emptyList<Any>(), waitingRoomData["finalEdges"])

        val completed = service.completeModelUpload(
            roomId = 1L,
            modelId = 10L,
            request = ModelUploadResultRequest(
                modelUrl = "https://example.com/models/room.glb",
                fileSizeMb = BigDecimal("12.34"),
                format = "GLB"
            )
        )
        val finalStatus = service.getModelStatus(1L)
        val completedRoomData = finalStatus.roomData as Map<*, *>

        assertEquals(ModelStatus.SUCCESS, completed.status)
        assertEquals("https://example.com/models/room.glb", completed.modelUrl)
        assertEquals(ModelStatus.SUCCESS, finalStatus.status)
        assertEquals("https://example.com/models/room.glb", finalStatus.modelUrl)
        assertEquals(BigDecimal("12.34"), model.fileSizeMb)
        assertEquals("glb", model.format)
        assertEquals(1, (completedRoomData["finalVertices"] as List<*>).size)
        assertEquals(emptyList<Any>(), completedRoomData["finalEdges"])
    }

    @Test
    fun `상태 조회에서는 저장된 roomData JSON을 객체로 반환한다`() {
        val model = Room3DModel(
            room = createRoom(),
            aiRoomId = "ai-room-id",
            originalImageUrl = "https://example.com/room.jpg",
            status = ModelStatus.WAITING_FOR_CORRECTION,
            roomData = """{"vertices":[],"edges":[]}"""
        )
        Mockito.`when`(modelRepository.findTopByRoomIdOrderByUploadedAtDesc(1L))
            .thenReturn(Optional.of(model))

        val response = service.getModelStatus(1L)
        val roomData = response.roomData as Map<*, *>

        assertEquals(emptyList<Any>(), roomData["vertices"])
        assertEquals(emptyList<Any>(), roomData["edges"])
    }

    private fun createRoom(): Room {
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
        return Room(
            id = 1L,
            property = property,
            owner = owner,
            roomName = "테스트 방",
            pricePerNight = 100_000,
            maxGuests = 2
        )
    }

    private class RecordingAiServerClient : AiServerClient("http://localhost:8000") {
        var analyzeRequest: AiAnalyzeRequestDto? = null
        val executionOrder = mutableListOf<String>()

        override fun analyze(request: AiAnalyzeRequestDto): Mono<Void> {
            analyzeRequest = request
            return Mono.empty()
        }

        override fun sendCorrection(
            aiRoomId: String,
            request: CorrectionRequestDto
        ): Mono<Void> {
            return Mono.fromRunnable<Void> {
                executionOrder.add("correction")
            }
        }

        override fun reconstruct(aiRoomId: String): Mono<Void> {
            return Mono.fromRunnable<Void> {
                executionOrder.add("reconstruct")
            }
        }
    }
}
