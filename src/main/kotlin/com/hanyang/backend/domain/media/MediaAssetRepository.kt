package com.hanyang.backend.domain.media

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
interface MediaAssetRepository : JpaRepository<MediaAsset, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MediaAsset a where a.id = :id")
    fun findLocked(id: Long): MediaAsset?
    fun findFirstBySourceModelIdAndUnitIdAndStatusOrderByIdAsc(sourceModelId: Long, unitId: Long, status: MediaStatus): MediaAsset?
    fun findByUnitIdAndOwnerUserIdAndStatus(unitId: Long, ownerUserId: Long, status: MediaStatus, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<MediaAsset>
}
interface UploadSessionRepository : JpaRepository<UploadSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UploadSession s where s.id = :id")
    fun findLocked(id: String): UploadSession?
}
