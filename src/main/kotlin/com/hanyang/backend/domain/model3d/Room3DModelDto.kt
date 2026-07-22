package com.hanyang.backend.domain.model3d

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

// ===============================
// 프론트 -> 백엔드 요청 DTO
// ===============================

// 프론트가 S3 이미지 URL을 백엔드에 넘길 때 사용
data class AnalyzeModelRequest(
    @field:NotEmpty(message = "이미지 URL은 최소 1개 이상 필요합니다.")
    val imageUrls: List<@NotBlank(message = "비어 있는 이미지 URL은 전송할 수 없습니다.") String>
)
// 프론트가 보정한 vertices/edges를 백엔드에 넘길 때 사용
// 동시에 AI 서버의 CorrectionRequestDTO 형식과도 맞춤
data class CorrectionRequestDto(
    @field:NotEmpty(message = "보정된 vertex는 최소 1개 이상 필요합니다.")
    val vertices: List<VertexDto>,
    val edges: List<EdgeDto>
)


// ===============================
// 공통 좌표/간선 DTO
// ===============================

data class VertexDto(
    val id: String,          // uuid 문자열
    val x: Double,
    val y: Double,
    val z: Double,
    val imageIndex: Int? = null
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

    val aiVertices: List<VertexDto> = emptyList(),
    val aiEdges: List<EdgeDto> = emptyList(),

    val correctedVertices: List<VertexDto> = emptyList(),
    val correctedEdges: List<EdgeDto> = emptyList(),

    val isCorrected: Boolean = false,

    val finalVertices: List<VertexDto> = emptyList(),
    val finalEdges: List<EdgeDto> = emptyList(),

    @field:JsonAlias("model_url", "glbUrl", "glb_url", "resultUrl", "result_url")
    val modelUrl: String? = null
)

