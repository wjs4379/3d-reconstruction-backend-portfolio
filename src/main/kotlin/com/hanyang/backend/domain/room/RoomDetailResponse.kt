package com.hanyang.backend.domain.room

// 프론트엔드 합의 명세 포맷 DTO
data class RoomDetailResponse(
    val roomId: String,
    val status: String,
    val message: String,
    val roomData: RoomDataDto
)

data class RoomDataDto(
    val vertices: List<VertexDto>,
    val surfaces: SurfaceDto
)

data class VertexDto(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class SurfaceDto(
    val floor: List<String>,
    val ceiling: List<String>,
    val walls: List<List<String>>
)