package com.hanyang.backend.domain.model3d

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal

// ===============================
// 프론트 -> 백엔드 요청 DTO
// ===============================

// 프론트가 S3 이미지 URL을 백엔드에 넘길 때 사용
data class AnalyzeModelRequest(
    @field:Size(min = AiPayloadLimits.SOURCE_IMAGE_COUNT, max = AiPayloadLimits.SOURCE_IMAGE_COUNT, message = "같은 공간을 촬영한 사진 4장이 필요합니다.")
    val imageUrls: List<@NotBlank(message = "비어 있는 이미지 URL은 전송할 수 없습니다.") String>
)

// 프론트가 보정한 vertices/edges를 백엔드에 넘길 때 사용
// 동시에 AI 서버의 CorrectionRequestDTO 형식과도 맞춤
data class CorrectionRequestDto(
    @field:NotEmpty(message = "보정된 vertex는 최소 1개 이상 필요합니다.")
    val vertices: List<VertexDto>,
    val edges: List<EdgeDto>
)

// AI가 생성한 최종 좌표로 프론트가 모델 파일을 만든 뒤 업로드 결과를 전달할 때 사용
data class ModelUploadResultRequest(
    @field:NotBlank(message = "최종 모델 URL은 필수입니다.")
    @field:Size(max = 2048, message = "최종 모델 URL은 2048자를 초과할 수 없습니다.")
    val modelUrl: String,

    @field:Positive(message = "파일 크기는 양수여야 합니다.")
    @field:Digits(integer = 8, fraction = 2, message = "파일 크기는 정수 8자리와 소수 2자리까지 허용됩니다.")
    val fileSizeMb: BigDecimal? = null,

    @field:Size(max = 20, message = "파일 포맷은 20자를 초과할 수 없습니다.")
    val format: String? = null
)


// ===============================
// 공통 좌표/간선 DTO
// ===============================

data class VertexDto(
    val id: String,          // uuid 문자열
    val x: Double,
    val y: Double,
    val z: Double,
    val imageIndex: Int? = null,
    val sourceVertexId: String? = null
)

data class EdgeDto(
    val id: String,          // uuid 문자열
    val startVertexId: String,
    val endVertexId: String
)




// ===============================
// 백엔드 -> 프론트 응답 DTO
// ===============================

data class ModelStatusResponse(
    val modelId: Long?,
    val roomId: Long?,
    val status: ModelStatus,
    val modelUrl: String?,
    val roomData: Any?
)


// ===============================
// 백엔드 -> AI 서버 요청 DTO
// ===============================

// AI 서버 POST /api/v1/rooms/analyze 요청 body
// Swagger 기준 필드명이 imagePaths임.
// 값은 일단 S3 URL을 넣되, AI 서버가 URL 다운로드 처리 가능해야 함.
data class AiAnalyzeRequestDto(
    val imagePaths: List<String>,
    val roomId: String,      // uuid
    val roomName: String,
    val userId: String,       // uuid
    val webhookUrl: String
)


// ===============================
// AI 서버 -> 백엔드 응답 DTO
// ===============================

// AI 서버 RoomResponse 구조
data class AiRoomResponseDto(
    val id: String,
    val roomName: String? = null,
    val userId: String? = null,
    val status: String? = null,

    val aiVertices: List<VertexDto>? = null,
    val aiEdges: List<EdgeDto>? = null,

    val correctedVertices: List<VertexDto> = emptyList(),
    val correctedEdges: List<EdgeDto> = emptyList(),

    val isCorrected: Boolean = false,

    val finalVertices: List<VertexDto>? = null,
    val finalEdges: List<EdgeDto>? = null,
    val viewerUp: List<Double>? = null
)


/** Immutable correction command persisted with the RECONSTRUCTING state transition. */
data class AiCorrectionReconstructRequestDto(val aiRoomId: String, val correction: CorrectionRequestDto)
