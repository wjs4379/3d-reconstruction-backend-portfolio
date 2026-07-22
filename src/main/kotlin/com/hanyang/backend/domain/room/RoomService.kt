package com.hanyang.backend.domain.room

import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val propertyRepository: PropertyRepository,
    private val userRepository: UserRepository
) {

    /** 방 메타데이터를 생성합니다. 3D 분석 시작은 models/analyze API가 담당합니다. */
    @Transactional
    fun createRoom(request: RoomCreateRequest): RoomCreateResponse {
        val property = propertyRepository.findById(request.propertyId)
            .orElseThrow { IllegalArgumentException("해당 건물이 존재하지 않습니다 ID: ${request.propertyId}") }

        val owner = userRepository.findById(request.ownerId)
            .orElseThrow { IllegalArgumentException("해당 호스트 유저가 존재하지 않습니다 ID: ${request.ownerId}") }

        val room = roomRepository.save(
            Room(
                property = property,
                owner = owner,
                roomName = request.roomName,
                pricePerNight = request.pricePerNight,
                maxGuests = request.maxGuests
            )
        )

        val roomId = requireNotNull(room.id) {
            "방 저장 후 roomId가 생성되지 않았습니다."
        }

        return RoomCreateResponse(
            roomId = roomId,
            propertyId = request.propertyId,
            ownerId = request.ownerId,
            roomName = room.roomName,
            pricePerNight = room.pricePerNight,
            maxGuests = room.maxGuests
        )
    }
}
