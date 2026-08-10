package com.hanyang.backend.domain.model3d.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import java.util.UUID

@DataJpaTest
class OutboxEventRepositoryIntegrationTest @Autowired constructor(
    private val outboxRepository: OutboxEventRepository
) {
    private val now = LocalDateTime.of(2026, 8, 10, 12, 0)

    @Test
    fun `실행 시각이 지난 PENDING과 lease가 만료된 PROCESSING만 조회한다`() {
        val readyPending = saveEvent(
            status = OutboxStatus.PENDING,
            nextAttemptAt = now.minusSeconds(1)
        )
        saveEvent(
            status = OutboxStatus.PENDING,
            nextAttemptAt = now.plusSeconds(1)
        )
        val expiredProcessing = saveEvent(
            status = OutboxStatus.PROCESSING,
            nextAttemptAt = now.minusMinutes(1),
            lockToken = "expired-token",
            lockedUntil = now.minusSeconds(1)
        )
        saveEvent(
            status = OutboxStatus.PROCESSING,
            nextAttemptAt = now.minusMinutes(1),
            lockToken = "active-token",
            lockedUntil = now.plusSeconds(1)
        )
        saveEvent(
            status = OutboxStatus.SENT,
            nextAttemptAt = now.minusMinutes(1)
        )

        val readyIds = outboxRepository.findReadyForUpdate(
            pendingStatus = OutboxStatus.PENDING,
            processingStatus = OutboxStatus.PROCESSING,
            now = now,
            pageable = PageRequest.of(0, 10)
        ).map { requireNotNull(it.id) }

        assertEquals(
            setOf(requireNotNull(readyPending.id), requireNotNull(expiredProcessing.id)),
            readyIds.toSet()
        )
    }

    @Test
    fun `현재 lock token 소유자만 SENT로 완료할 수 있다`() {
        val event = saveEvent(
            status = OutboxStatus.PROCESSING,
            nextAttemptAt = now.minusMinutes(1),
            lockToken = "current-token",
            lockedUntil = now.plusSeconds(30)
        )
        val eventId = requireNotNull(event.id)

        val staleUpdate = outboxRepository.markSentIfOwned(
            eventId = eventId,
            lockToken = "stale-token",
            processingStatus = OutboxStatus.PROCESSING,
            sentStatus = OutboxStatus.SENT,
            now = now
        )
        val afterStaleUpdate = outboxRepository.findById(eventId).orElseThrow()

        assertEquals(0, staleUpdate)
        assertEquals(OutboxStatus.PROCESSING, afterStaleUpdate.status)
        assertEquals("current-token", afterStaleUpdate.lockToken)

        val ownerUpdate = outboxRepository.markSentIfOwned(
            eventId = eventId,
            lockToken = "current-token",
            processingStatus = OutboxStatus.PROCESSING,
            sentStatus = OutboxStatus.SENT,
            now = now
        )
        val sent = outboxRepository.findById(eventId).orElseThrow()

        assertEquals(1, ownerUpdate)
        assertEquals(OutboxStatus.SENT, sent.status)
        assertEquals(now, sent.sentAt)
        assertNull(sent.lockToken)
        assertNull(sent.lockedUntil)
    }

    @Test
    fun `현재 lock token 소유자만 재시도를 예약할 수 있다`() {
        val event = saveEvent(
            status = OutboxStatus.PROCESSING,
            nextAttemptAt = now.minusMinutes(1),
            lockToken = "current-token",
            lockedUntil = now.plusSeconds(30)
        )
        val eventId = requireNotNull(event.id)
        val nextAttemptAt = now.plusSeconds(4)

        val staleUpdate = outboxRepository.scheduleRetryIfOwned(
            eventId = eventId,
            lockToken = "stale-token",
            processingStatus = OutboxStatus.PROCESSING,
            pendingStatus = OutboxStatus.PENDING,
            nextAttemptAt = nextAttemptAt,
            lastError = "timeout",
            now = now
        )

        assertEquals(0, staleUpdate)
        assertEquals(
            OutboxStatus.PROCESSING,
            outboxRepository.findById(eventId).orElseThrow().status
        )

        val ownerUpdate = outboxRepository.scheduleRetryIfOwned(
            eventId = eventId,
            lockToken = "current-token",
            processingStatus = OutboxStatus.PROCESSING,
            pendingStatus = OutboxStatus.PENDING,
            nextAttemptAt = nextAttemptAt,
            lastError = "timeout",
            now = now
        )
        val pending = outboxRepository.findById(eventId).orElseThrow()

        assertEquals(1, ownerUpdate)
        assertEquals(OutboxStatus.PENDING, pending.status)
        assertEquals(nextAttemptAt, pending.nextAttemptAt)
        assertEquals("timeout", pending.lastError)
        assertNull(pending.lockToken)
        assertNull(pending.lockedUntil)
    }

    @Test
    fun `현재 lock token 소유자만 DEAD로 종료할 수 있다`() {
        val event = saveEvent(
            status = OutboxStatus.PROCESSING,
            nextAttemptAt = now.minusMinutes(1),
            lockToken = "current-token",
            lockedUntil = now.plusSeconds(30)
        )
        val eventId = requireNotNull(event.id)

        val staleUpdate = outboxRepository.markDeadIfOwned(
            eventId = eventId,
            lockToken = "stale-token",
            processingStatus = OutboxStatus.PROCESSING,
            deadStatus = OutboxStatus.DEAD,
            lastError = "invalid payload",
            now = now
        )

        assertEquals(0, staleUpdate)
        assertEquals(
            OutboxStatus.PROCESSING,
            outboxRepository.findById(eventId).orElseThrow().status
        )

        val ownerUpdate = outboxRepository.markDeadIfOwned(
            eventId = eventId,
            lockToken = "current-token",
            processingStatus = OutboxStatus.PROCESSING,
            deadStatus = OutboxStatus.DEAD,
            lastError = "invalid payload",
            now = now
        )
        val dead = outboxRepository.findById(eventId).orElseThrow()

        assertEquals(1, ownerUpdate)
        assertEquals(OutboxStatus.DEAD, dead.status)
        assertEquals("invalid payload", dead.lastError)
        assertNull(dead.lockToken)
        assertNull(dead.lockedUntil)
    }

    private fun saveEvent(
        status: OutboxStatus,
        nextAttemptAt: LocalDateTime,
        lockToken: String? = null,
        lockedUntil: LocalDateTime? = null
    ): OutboxEvent {
        val uniqueId = UUID.randomUUID().toString()
        return outboxRepository.saveAndFlush(
            OutboxEvent(
                idempotencyKey = uniqueId,
                eventType = OutboxEventType.AI_ANALYZE,
                aggregateId = uniqueId,
                payload = "{}",
                status = status,
                nextAttemptAt = nextAttemptAt,
                lockToken = lockToken,
                lockedUntil = lockedUntil,
                createdAt = now.minusMinutes(1),
                updatedAt = now.minusMinutes(1)
            )
        )
    }
}
