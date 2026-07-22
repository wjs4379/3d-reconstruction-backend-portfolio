package com.hanyang.backend.domain.room

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

enum class RenderStatus {
    ANALYZING,
    WAITING_FOR_CORRECTION,
    RECONSTRUCTING,
    SUCCESS,
    FAILED
}

// 방 메타데이터 생성 요청/응답
data class RoomCreateRequest(
    @field:NotNull(message = "propertyId는 필수입니다.")
    @field:Positive(message = "propertyId는 양수여야 합니다.")
    val propertyId: Long,

    @field:NotNull(message = "ownerId는 필수입니다.")
    @field:Positive(message = "ownerId는 양수여야 합니다.")
    val ownerId: Long,

    @field:NotBlank(message = "방 이름은 필수입니다.")
    val roomName: String,

    @field:NotNull(message = "1박 가격은 필수입니다.")
    @field:Positive(message = "1박 가격은 양수여야 합니다.")
    val pricePerNight: Int,

    @field:NotNull(message = "수용 인원은 필수입니다.")
    @field:Positive(message = "수용 인원은 양수여야 합니다.")
    val maxGuests: Int
)

data class RoomCreateResponse(
    val roomId: Long,
    val propertyId: Long,
    val ownerId: Long,
    val roomName: String,
    val pricePerNight: Int,
    val maxGuests: Int
)


// AI 서버가 백엔드로 전달하는 분석·재구성 결과
data class AiWebhookRequest(
    val aiRoomId: String,
    val status: RenderStatus,
    val modelUrl: String?,
    val fileSizeMb: BigDecimal?,
    val format: String?,
    val roomDataJson: String?
)
