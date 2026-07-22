package com.hanyang.backend.domain.model3d

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface Room3DModelRepository : JpaRepository<Room3DModel, Long> {
    // 특정 방(Room)에 링크된 3D 모델 정보를 가져올 때 쓸 거야
    fun findByRoomId(roomId: Long): Optional<Room3DModel>

    fun findTopByRoomIdOrderByUploadedAtDesc(roomId: Long): Optional<Room3DModel>

    fun findByAiRoomId(aiRoomId: String): Optional<Room3DModel>


}