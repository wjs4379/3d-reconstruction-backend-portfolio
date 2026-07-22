package com.hanyang.backend.domain.room

import com.hanyang.backend.domain.usage.RoomUsageRequest
import com.hanyang.backend.domain.usage.RoomUsageResponse
import com.hanyang.backend.domain.usage.RoomUsageService
import com.hanyang.backend.domain.usage.UserUsageHistoryResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api")
class RoomUsageController(    private val roomUsageService: RoomUsageService
) {

    @PostMapping("/rooms/{roomId}/usages")
    fun rentRoom(
        @PathVariable roomId: Long,
        @RequestBody @Valid request: RoomUsageRequest
    ): RoomUsageResponse {

        val savedUsage = roomUsageService.rentRoom(roomId, request)

        return RoomUsageResponse(
            usageId = savedUsage.id,
            roomId = savedUsage.room.id,
            guestName = savedUsage.user.name,
            status = savedUsage.status,
            startedAt = savedUsage.startedAt,
            endedAt = savedUsage.endedAt
        )
    }

    @GetMapping("/users/{userId}/usages")
    fun getUserUsages(@PathVariable userId: Long): List<UserUsageHistoryResponse> {
        return roomUsageService.getUserUsageHistory(userId)
    }

    @PostMapping("/usages/{usageId}/complete")
    fun completeUsage(@PathVariable usageId: Long): RoomUsageResponse {
        return roomUsageService.completeRental(usageId)
    }
}