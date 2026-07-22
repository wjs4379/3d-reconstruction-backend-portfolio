package com.hanyang.backend.domain.usage

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

// 방 대여 신청 요청 DTO
data class RoomUsageRequest(
    @field:NotNull(message = "빌리는 사람(userId) 정보는 필수입니다.")
    val userId: Long,

    @field:NotNull(message = "대여 시작일은 필수입니다.")
    val startedAt: LocalDateTime,

    @field:NotNull(message = "대여 종료일은 필수입니다.")
    val endedAt: LocalDateTime
)

// 대여 완료 응답 DTO
data class RoomUsageResponse(
    val usageId: Long?,
    val roomId: Long?,
    val guestName: String,
    val status: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime
)


// 내 대여 내역 리스트 조회용 응답 DTO
data class UserUsageHistoryResponse(
    val usageId: Long?,
    val roomId: Long?,
    val roomTitle: String, // 👈 방 이름 추가해서 프론트 편하게 해주기
    val status: String,
    val startedAt: java.time.LocalDateTime,
    val endedAt: java.time.LocalDateTime
)