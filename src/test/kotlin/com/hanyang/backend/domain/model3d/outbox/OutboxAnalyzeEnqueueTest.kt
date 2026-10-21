package com.hanyang.backend.domain.model3d.outbox

import com.hanyang.backend.domain.model3d.AiAnalyzeRequestDto
import com.hanyang.backend.domain.model3d.AiServerClient
import com.hanyang.backend.domain.model3d.AnalyzeModelRequest
import com.hanyang.backend.domain.model3d.ModelStatus
import com.hanyang.backend.domain.model3d.Room3DModel
import com.hanyang.backend.domain.model3d.Room3DModelRepository
import com.hanyang.backend.domain.model3d.Room3DModelService
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.util.Optional

class OutboxAnalyzeEnqueueTest {
    @Test
    fun `분석 모델과 모든 이미지 URL을 담은 Outbox를 저장하고 AI를 즉시 호출하지 않는다`() {
        val roomRepository = Mockito.mock(RoomRepository::class.java)
        val modelRepository = Mockito.mock(Room3DModelRepository::class.java)
        val outboxRepository = Mockito.mock(OutboxEventRepository::class.java)
        val aiServerClient = Mockito.mock(AiServerClient::class.java)
        val jsonMapper = JsonMapper.builder().build()
        val room = createRoom()
        val imageUrls = listOf(
            "https://example.com/room-1.jpg",
            "https://example.com/room-2.jpg",
            "https://example.com/room-3.jpg",
            "https://example.com/room-4.jpg"
        )
        var savedEvent: OutboxEvent? = null

        Mockito.`when`(roomRepository.findById(1L)).thenReturn(Optional.of(room))
        Mockito.`when`(modelRepository.save(any(Room3DModel::class.java)))
            .thenAnswer { it.getArgument(0) }
        Mockito.`when`(outboxRepository.save(any(OutboxEvent::class.java)))
            .thenAnswer { invocation ->
                invocation.getArgument<OutboxEvent>(0).also { savedEvent = it }
            }

        val service = Room3DModelService(
            roomRepository = roomRepository,
            room3DModelRepository = modelRepository,
            outboxEventRepository = outboxRepository,
            aiServerClient = aiServerClient,
            jsonMapper = jsonMapper,
            transactionOperations = TransactionOperations.withoutTransaction(),
            outboxEnabled = true,
            webhookUrl = "http://localhost:8080/api/webhook"
        )

        val response = service.analyzeRoom(1L, AnalyzeModelRequest(imageUrls))

        assertEquals(ModelStatus.ANALYZING, response.status)
        assertNotNull(savedEvent)
        val persistedRequest = jsonMapper.readValue(
            requireNotNull(savedEvent).payload,
            AiAnalyzeRequestDto::class.java
        )
        assertEquals(imageUrls, persistedRequest.imagePaths)
        assertEquals(requireNotNull(savedEvent).aggregateId, persistedRequest.roomId)
        Mockito.verifyNoInteractions(aiServerClient)
    }

    private fun createRoom(): Room {
        val property = Property(
            id = 1L,
            title = "Test Property",
            address = "Seoul"
        )
        val owner = User(
            id = 1L,
            email = "owner@example.com",
            name = "Owner",
            provider = "LOCAL",
            providerId = "owner-1",
            role = "HOST"
        )
        return Room(
            id = 1L,
            property = property,
            owner = owner,
            roomName = "Test Room",
            pricePerNight = 10000,
            maxGuests = 2
        )
    }
}
