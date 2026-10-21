package com.hanyang.backend.domain.model3d

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

@Repository
interface Room3DModelRepository : JpaRepository<Room3DModel, Long> {
    // 특정 방(Room)에 링크된 3D 모델 정보를 가져올 때 쓸 거야
    fun findByRoomId(roomId: Long): Optional<Room3DModel>

    fun findTopByRoomIdOrderByUploadedAtDesc(roomId: Long): Optional<Room3DModel>

    fun findByAiRoomId(aiRoomId: String): Optional<Room3DModel>

    fun findTop100ByRoomIdOrderByUploadedAtDescIdDesc(roomId: Long): List<Room3DModel>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findFirstByRoomIdOrderByUploadedAtDesc(roomId: Long): Optional<Room3DModel>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByIdAndRoomId(id: Long, roomId: Long): Optional<Room3DModel>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select model from Room3DModel model where model.aiRoomId = :aiRoomId")
    fun findByAiRoomIdForUpdate(
        @Param("aiRoomId") aiRoomId: String
    ): Optional<Room3DModel>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        update Room3DModel model
           set model.status = :failedStatus,
               model.roomData = coalesce(model.roomData, :errorRoomData)
         where model.aiRoomId = :aiRoomId
           and model.status = :expectedStatus
        """
    )
    fun markFailedIfCurrentStatus(
        @Param("aiRoomId") aiRoomId: String,
        @Param("expectedStatus") expectedStatus: ModelStatus,
        @Param("failedStatus") failedStatus: ModelStatus,
        @Param("errorRoomData") errorRoomData: String
    ): Int

}
