package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest

@DataJpaTest
class Room3DModelRepositoryTest @Autowired constructor(
    private val modelRepository: Room3DModelRepository,
    private val roomRepository: RoomRepository,
    private val propertyRepository: PropertyRepository,
    private val userRepository: UserRepository
) {
    private lateinit var room: Room

    @BeforeEach
    fun setUp() {
        val property = propertyRepository.save(
            Property(
                title = "테스트 숙소",
                description = null,
                address = "서울"
            )
        )
        val owner = userRepository.save(
            User(
                email = "owner@example.com",
                name = "테스트 호스트",
                provider = "GOOGLE",
                providerId = "repository-test-owner",
                role = "HOST"
            )
        )
        room = roomRepository.save(
            Room(
                property = property,
                owner = owner,
                roomName = "테스트 방",
                pricePerNight = 100_000,
                maxGuests = 2
            )
        )
    }

    @Test
    fun `현재 상태가 예상 상태일 때만 비동기 전송 실패를 반영한다`() {
        val model = saveModel("expected-status", ModelStatus.RECONSTRUCTING)

        val updated = modelRepository.markFailedIfCurrentStatus(
            aiRoomId = model.aiRoomId,
            expectedStatus = ModelStatus.RECONSTRUCTING,
            failedStatus = ModelStatus.FAILED,
            errorRoomData = """{"code":"AI_REQUEST_FAILED"}"""
        )
        val saved = modelRepository.findById(requireNotNull(model.id)).orElseThrow()

        assertEquals(1, updated)
        assertEquals(ModelStatus.FAILED, saved.status)
        assertEquals("""{"code":"AI_REQUEST_FAILED"}""", saved.roomData)
    }

    @Test
    fun `현재 상태가 이미 진행됐으면 늦은 비동기 전송 실패를 무시한다`() {
        val model = saveModel("advanced-status", ModelStatus.WAITING_FOR_MODEL_UPLOAD)

        val updated = modelRepository.markFailedIfCurrentStatus(
            aiRoomId = model.aiRoomId,
            expectedStatus = ModelStatus.RECONSTRUCTING,
            failedStatus = ModelStatus.FAILED,
            errorRoomData = """{"code":"AI_REQUEST_FAILED"}"""
        )
        val saved = modelRepository.findById(requireNotNull(model.id)).orElseThrow()

        assertEquals(0, updated)
        assertEquals(ModelStatus.WAITING_FOR_MODEL_UPLOAD, saved.status)
        assertNull(saved.roomData)
    }

    @Test
    fun `기존 좌표 데이터는 비동기 전송 오류 정보로 덮어쓰지 않는다`() {
        val originalRoomData = """{"aiVertices":[],"aiEdges":[]}"""
        val model = saveModel(
            aiRoomId = "preserve-room-data",
            status = ModelStatus.RECONSTRUCTING,
            roomData = originalRoomData
        )

        val updated = modelRepository.markFailedIfCurrentStatus(
            aiRoomId = model.aiRoomId,
            expectedStatus = ModelStatus.RECONSTRUCTING,
            failedStatus = ModelStatus.FAILED,
            errorRoomData = """{"code":"AI_REQUEST_FAILED"}"""
        )
        val saved = modelRepository.findById(requireNotNull(model.id)).orElseThrow()

        assertEquals(1, updated)
        assertEquals(ModelStatus.FAILED, saved.status)
        assertEquals(originalRoomData, saved.roomData)
    }

    private fun saveModel(
        aiRoomId: String,
        status: ModelStatus,
        roomData: String? = null
    ): Room3DModel {
        return modelRepository.saveAndFlush(
            Room3DModel(
                room = room,
                aiRoomId = aiRoomId,
                originalImageUrl = "https://example.com/room.jpg",
                status = status,
                roomData = roomData
            )
        )
    }
}
