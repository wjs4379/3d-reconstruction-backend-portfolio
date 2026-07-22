package com.hanyang.backend.domain.room

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RoomRepository : JpaRepository<Room, Long> {
    // 특정 건물(Property)에 속한 방들만 싹 다 조회할 때 쓸 쿼리 메소드야
    fun findByPropertyId(propertyId: Long): List<Room>
}