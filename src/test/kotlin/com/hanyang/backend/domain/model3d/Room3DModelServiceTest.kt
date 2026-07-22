package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.room.RenderStatus
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.util.Optional

class Room3DModelServiceTest {

    private lateinit var roomRepository: RoomRepository
    private lateinit var room3DModelRepository: Room3DModelRepository
    private lateinit var aiServerClient: AiServerClient
    private lateinit var jsonMapper: JsonMapper
    private lateinit var service: Room3DModelService

    private val aiRoomId = "test-ai-room-id"

    @BeforeEach
    fun setUp() {
        roomRepository = Mockito.mock(RoomRepository::class.java)
        room3DModelRepository = Mockito.mock(Room3DModelRepository::class.java)
        aiServerClient = Mockito.mock(AiServerClient::class.java)
        jsonMapper = JsonMapper.builder().build()

        service = Room3DModelService(
            roomRepository = roomRepository,
            room3DModelRepository = room3DModelRepository,
            aiServerClient = aiServerClient,
            jsonMapper = jsonMapper,
            webhookUrl = "http://localhost:8080/api/webhook"
        )
    }

    @Test
    fun `분석 중에 보정 대기 웹훅을 받으면 WAITING_FOR_CORRECTION으로 변경된다`() {
        val model = createModel(ModelStatus.ANALYZING)
        val roomData = """{"aiVertices":[],"aiEdges":[]}"""

        Mockito.`when`(room3DModelRepository.findByAiRoomId(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.WAITING_FOR_CORRECTION,
            modelUrl = null,
            fileSizeMb = null,
            format = null,
            roomDataJson = roomData
        )

        service.handleAiWebhook(request)

        assertEquals(ModelStatus.WAITING_FOR_CORRECTION, model.status)
        assertEquals(roomData, model.roomData)
    }

    @Test
    fun `재구성 중이어도 모델 URL 없는 성공 웹훅이면 상태를 유지한다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)
        val roomData = """{"finalVertices":[],"finalEdges":[]}"""

        Mockito.`when`(room3DModelRepository.findByAiRoomId(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            modelUrl = null,
            fileSizeMb = null,
            format = null,
            roomDataJson = roomData
        )

        service.handleAiWebhook(request)

        assertEquals(ModelStatus.RECONSTRUCTING, model.status)
        assertEquals(roomData, model.roomData)
    }

    @Test
    fun `재구성 중에 모델 URL을 포함한 성공 웹훅을 받으면 SUCCESS로 변경된다`() {
        val model = createModel(ModelStatus.RECONSTRUCTING)

        Mockito.`when`(room3DModelRepository.findByAiRoomId(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            modelUrl = "https://example.com/model.glb",
            fileSizeMb = null,
            format = "glb",
            roomDataJson = """{"finalVertices":[],"finalEdges":[]}"""
        )

        service.handleAiWebhook(request)

        assertEquals(ModelStatus.SUCCESS, model.status)
        assertEquals("https://example.com/model.glb", model.modelUrl)
    }

    @Test
    fun `이미 성공한 모델에 늦은 보정 대기 웹훅이 와도 상태와 결과를 유지한다`() {
        val model = createModel(ModelStatus.SUCCESS)
        model.roomData = """{"finalVertices":[{"id":"final"}]}"""
        val finalRoomData = model.roomData

        Mockito.`when`(room3DModelRepository.findByAiRoomId(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.WAITING_FOR_CORRECTION,
            modelUrl = null,
            fileSizeMb = null,
            format = null,
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

        Mockito.`when`(room3DModelRepository.findByAiRoomId(aiRoomId))
            .thenReturn(Optional.of(model))

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            modelUrl = "https://example.com/stale.glb",
            fileSizeMb = null,
            format = null,
            roomDataJson = """{"version":"stale"}"""
        )

        service.handleAiWebhook(request)

        assertEquals("https://example.com/final.glb", model.modelUrl)
        assertEquals("""{"version":"final"}""", model.roomData)
    }

    @Test
    fun `존재하지 않는 aiRoomId이면 예외가 발생한다`() {
        Mockito.`when`(room3DModelRepository.findByAiRoomId(aiRoomId))
            .thenReturn(Optional.empty())

        val request = AiWebhookRequest(
            aiRoomId = aiRoomId,
            status = RenderStatus.SUCCESS,
            modelUrl = null,
            fileSizeMb = null,
            format = null,
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

    private fun createModel(status: ModelStatus): Room3DModel {
        val room = Mockito.mock(Room::class.java)

        return Room3DModel(
            room = room,
            aiRoomId = aiRoomId,
            originalImageUrl = "https://example.com/room.jpg",
            status = status
        )
    }
}
