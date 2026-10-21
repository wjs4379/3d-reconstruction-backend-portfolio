package com.hanyang.backend.domain.inquiry

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface InquiryRepository : JpaRepository<Inquiry, Long> {
    fun findByAuthorUserId(authorUserId: Long, pageable: Pageable): Page<Inquiry>
    fun findByUnitId(unitId: Long, pageable: Pageable): Page<Inquiry>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inquiry i where i.id = :id")
    fun findForUpdate(id: Long): Inquiry?
}
