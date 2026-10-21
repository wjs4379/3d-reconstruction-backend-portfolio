package com.hanyang.backend.domain.room

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

@Repository
interface RoomRepository : JpaRepository<Room, Long> {
    // 특정 건물(Property)에 속한 방들만 싹 다 조회할 때 쓸 쿼리 메소드야
    fun findByUnitId(unitId: Long): List<Room>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    fun findForUpdate(id: Long): Room?
    fun findByPropertyId(propertyId: Long): List<Room>
}