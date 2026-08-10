package com.hanyang.backend.domain.model3d.outbox

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): Optional<OutboxEvent>

    fun countByStatus(status: OutboxStatus): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select event
          from OutboxEvent event
         where (event.status = :pendingStatus and event.nextAttemptAt <= :now)
            or (event.status = :processingStatus and event.lockedUntil <= :now)
         order by event.id
        """
    )
    fun findReadyForUpdate(
        @Param("pendingStatus") pendingStatus: OutboxStatus,
        @Param("processingStatus") processingStatus: OutboxStatus,
        @Param("now") now: LocalDateTime,
        pageable: Pageable
    ): List<OutboxEvent>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update OutboxEvent event
           set event.status = :sentStatus,
               event.sentAt = :now,
               event.updatedAt = :now,
               event.lockToken = null,
               event.lockedUntil = null,
               event.lastError = null
         where event.id = :eventId
           and event.status = :processingStatus
           and event.lockToken = :lockToken
        """
    )
    fun markSentIfOwned(
        @Param("eventId") eventId: Long,
        @Param("lockToken") lockToken: String,
        @Param("processingStatus") processingStatus: OutboxStatus,
        @Param("sentStatus") sentStatus: OutboxStatus,
        @Param("now") now: LocalDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update OutboxEvent event
           set event.status = :pendingStatus,
               event.nextAttemptAt = :nextAttemptAt,
               event.updatedAt = :now,
               event.lockToken = null,
               event.lockedUntil = null,
               event.lastError = :lastError
         where event.id = :eventId
           and event.status = :processingStatus
           and event.lockToken = :lockToken
        """
    )
    fun scheduleRetryIfOwned(
        @Param("eventId") eventId: Long,
        @Param("lockToken") lockToken: String,
        @Param("processingStatus") processingStatus: OutboxStatus,
        @Param("pendingStatus") pendingStatus: OutboxStatus,
        @Param("nextAttemptAt") nextAttemptAt: LocalDateTime,
        @Param("lastError") lastError: String,
        @Param("now") now: LocalDateTime
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update OutboxEvent event
           set event.status = :deadStatus,
               event.updatedAt = :now,
               event.lockToken = null,
               event.lockedUntil = null,
               event.lastError = :lastError
         where event.id = :eventId
           and event.status = :processingStatus
           and event.lockToken = :lockToken
        """
    )
    fun markDeadIfOwned(
        @Param("eventId") eventId: Long,
        @Param("lockToken") lockToken: String,
        @Param("processingStatus") processingStatus: OutboxStatus,
        @Param("deadStatus") deadStatus: OutboxStatus,
        @Param("lastError") lastError: String,
        @Param("now") now: LocalDateTime
    ): Int
}
