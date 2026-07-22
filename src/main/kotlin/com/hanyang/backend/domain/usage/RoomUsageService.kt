package com.hanyang.backend.domain.usage

import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoomUsageService(
    private val roomUsageRepository: RoomUsageRepository,
    private val roomRepository: RoomRepository,
    private val userRepository: UserRepository
) {

    @Transactional
    fun rentRoom(roomId: Long, request: RoomUsageRequest): RoomUsage {
        val room = roomRepository.findById(roomId)
            .orElseThrow { IllegalArgumentException("해당 방이 존재하지 않습니다 ID: $roomId") }
        val user = userRepository.findById(request.userId)
            .orElseThrow { IllegalArgumentException("해당 게스트 유저가 존재하지 않습니다 ID: ${request.userId}") }

        val roomUsage = RoomUsage(
            user = user,
            room = room,
            status = "ACTIVE",
            startedAt = request.startedAt,
            endedAt = request.endedAt
        )
        return roomUsageRepository.save(roomUsage)
    }

    // 🌟 [추가 1] 특정 유저의 대여 내역 전체 조회 (Query Method 활용)
    @Transactional(readOnly = true)
    fun getUserUsageHistory(userId: Long): List<UserUsageHistoryResponse> {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("해당 유저가 존재하지 않습니다. ID: $userId") }

        // 유저가 빌린 모든 대여 이력 가져오기
        val usages = roomUsageRepository.findByUser(user)

        return usages.map { usage ->
            UserUsageHistoryResponse(
                usageId = usage.id,
                roomId = usage.room.id,
                roomTitle = usage.room.roomName, // 연관관계 그래프 탐색으로 방 이름 겟!
                status = usage.status,
                startedAt = usage.startedAt,
                endedAt = usage.endedAt
            )
        }
    }

    // 🌟 [추가 2] 대여 반납(종료) 처리 기능
    @Transactional
    fun completeRental(usageId: Long): RoomUsageResponse {
        val usage = roomUsageRepository.findById(usageId)
            .orElseThrow { IllegalArgumentException("해당 대여 이력이 존재하지 않습니다. ID: $usageId") }

        // 더티 체킹(Dirty Checking)으로 상태를 COMPLETED로 변경
        usage.status = "COMPLETED"

        return RoomUsageResponse(
            usageId = usage.id,
            roomId = usage.room.id,
            guestName = usage.user.name,
            status = usage.status,
            startedAt = usage.startedAt,
            endedAt = usage.endedAt
        )
    }
}