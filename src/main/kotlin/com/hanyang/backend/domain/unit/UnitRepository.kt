package com.hanyang.backend.domain.unit

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface UnitRepository : JpaRepository<Unit, Long> {
    fun findByCreatorUserId(creatorUserId: Long, pageable: Pageable): Page<Unit>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Unit u where u.id = :id")
    fun findForUpdate(id: Long): Unit?
}
