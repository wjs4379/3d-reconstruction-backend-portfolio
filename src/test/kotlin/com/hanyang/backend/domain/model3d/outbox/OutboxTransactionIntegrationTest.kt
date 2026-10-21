package com.hanyang.backend.domain.model3d.outbox

import com.hanyang.backend.domain.model3d.AiAnalyzeRequestDto
import com.hanyang.backend.domain.model3d.AnalyzeModelRequest
import com.hanyang.backend.domain.model3d.ModelStatus
import com.hanyang.backend.domain.model3d.Room3DModelRepository
import com.hanyang.backend.domain.model3d.Room3DModelService
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest
class OutboxTransactionIntegrationTest @Autowired constructor(
    private val propertyRepository: PropertyRepository,
    private val userRepository: UserRepository,
    private val roomRepository: RoomRepository,
    private val modelRepository: Room3DModelRepository,
    private val room3DModelService: Room3DModelService,
    private val jsonMapper: JsonMapper
) {
    @field:MockitoSpyBean
    private lateinit var outboxRepository: OutboxEventRepository

    @Test
    fun `분석 접수 트랜잭션은 모델과 PENDING Outbox를 함께 커밋한다`() {
        val room = createRoom()
        val imageUrls = listOf(
            "https://example.com/integration-1.jpg",
            "https://example.com/integration-2.jpg",
            "https://example.com/integration-3.jpg",
            "https://example.com/integration-4.jpg"
        )
        val existingEventIds = outboxRepository.findAll()
            .mapNotNull(OutboxEvent::id)
            .toSet()

        val response = room3DModelService.analyzeRoom(
            requireNotNull(room.id),
            AnalyzeModelRequest(imageUrls)
        )

        val model = modelRepository.findById(requireNotNull(response.modelId)).orElseThrow()
        val event = outboxRepository.findAll()
            .single { requireNotNull(it.id) !in existingEventIds }
        val persistedRequest = jsonMapper.readValue(
            event.payload,
            AiAnalyzeRequestDto::class.java
        )

        assertEquals(ModelStatus.ANALYZING, response.status)
        assertEquals(ModelStatus.ANALYZING, model.status)
        assertEquals(OutboxStatus.PENDING, event.status)
        assertEquals(imageUrls, persistedRequest.imagePaths)
        assertEquals(model.aiRoomId, persistedRequest.roomId)
        assertTrue(event.idempotencyKey.isNotBlank())
    }

    @Test
    fun `Outbox 저장이 실패하면 같은 트랜잭션의 분석 모델도 롤백한다`() {
        val room = createRoom()
        val modelCountBefore = modelRepository.count()
        val outboxCountBefore = outboxRepository.count()
        val expected = IllegalStateException("forced Outbox save failure")
        Mockito.doThrow(expected)
            .`when`(outboxRepository)
            .save(Mockito.any(OutboxEvent::class.java))

        val thrown = assertThrows(IllegalStateException::class.java) {
            room3DModelService.analyzeRoom(
                requireNotNull(room.id),
                AnalyzeModelRequest((1..4).map { "https://example.com/rollback-$it.jpg" })
            )
        }

        assertEquals(expected.message, thrown.message)
        assertEquals(modelCountBefore, modelRepository.count())
        assertEquals(outboxCountBefore, outboxRepository.count())
    }

    private fun createRoom(): Room {
        val suffix = UUID.randomUUID().toString()
        val property = propertyRepository.save(
            Property(
                title = "Outbox Integration Property $suffix",
                address = "Seoul"
            )
        )
        val owner = userRepository.save(
            User(
                email = "outbox-integration-$suffix@example.com",
                name = "Outbox Owner",
                provider = "LOCAL",
                providerId = "outbox-integration-owner-$suffix",
                role = "HOST"
            )
        )
        return roomRepository.save(
            Room(
                property = property,
                owner = owner,
                roomName = "Outbox Integration Room",
                pricePerNight = 10000,
                maxGuests = 2
            )
        )
    }
}
