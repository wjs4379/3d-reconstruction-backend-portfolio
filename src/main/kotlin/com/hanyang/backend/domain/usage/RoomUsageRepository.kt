package com.hanyang.backend.domain.usage

import com.hanyang.backend.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RoomUsageRepository : JpaRepository<RoomUsage, Long> {
    fun findByUserId(userId: Long): List<RoomUsage>
    fun findByUser(user: User): List<RoomUsage>
}