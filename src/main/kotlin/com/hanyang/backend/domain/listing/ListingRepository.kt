package com.hanyang.backend.domain.listing

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ListingRepository : JpaRepository<Listing, Long>, JpaSpecificationExecutor<Listing> {
    fun findByUnitIdOrderByIdAsc(unitId: Long): List<Listing>
    fun findByUnitIdAndDealType(unitId: Long, dealType: DealType): Listing?
    fun findByUnitCreatorUserId(creatorUserId: Long, pageable: Pageable): Page<Listing>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.unit.id = :unitId order by l.id asc")
    fun findAllForUnitUpdate(unitId: Long): List<Listing>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    fun findForUpdate(id: Long): Listing?
}
