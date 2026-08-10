package com.hanyang.backend.domain.model3d.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class OutboxWorkerTest {
    @Test
    fun `선점한 이벤트를 전송하고 SENT로 완료한다`() {
        val dispatchService = Mockito.mock(OutboxDispatchService::class.java)
        val events = listOf(claimed(1L), claimed(2L))
        Mockito.`when`(dispatchService.claimBatch(2)).thenReturn(events)
        events.forEach { event ->
            Mockito.`when`(dispatchService.send(event)).thenReturn(Mono.empty())
            Mockito.`when`(dispatchService.markSent(event)).thenReturn(true)
        }

        val processed = worker(dispatchService, batchSize = 4, maxConcurrency = 2)
            .processOnce()

        assertEquals(2, processed)
        events.forEach { event ->
            Mockito.verify(dispatchService).send(event)
            Mockito.verify(dispatchService).markSent(event)
        }
    }

    @Test
    fun `한 이벤트 전송이 실패해도 실패를 기록하고 다음 이벤트를 계속 처리한다`() {
        val dispatchService = Mockito.mock(OutboxDispatchService::class.java)
        val failedEvent = claimed(1L)
        val successfulEvent = claimed(2L)
        val error = TimeoutException("fake timeout")
        Mockito.`when`(dispatchService.claimBatch(2))
            .thenReturn(listOf(failedEvent, successfulEvent))
        Mockito.`when`(dispatchService.send(failedEvent)).thenReturn(Mono.error(error))
        Mockito.`when`(dispatchService.markFailure(failedEvent, error))
            .thenReturn(DispatchFailureOutcome.RETRY_SCHEDULED)
        Mockito.`when`(dispatchService.send(successfulEvent)).thenReturn(Mono.empty())
        Mockito.`when`(dispatchService.markSent(successfulEvent)).thenReturn(true)

        val processed = worker(dispatchService, batchSize = 2, maxConcurrency = 2)
            .processOnce()

        assertEquals(2, processed)
        Mockito.verify(dispatchService).markFailure(failedEvent, error)
        Mockito.verify(dispatchService, Mockito.never()).markSent(failedEvent)
        Mockito.verify(dispatchService).send(successfulEvent)
        Mockito.verify(dispatchService).markSent(successfulEvent)
    }

    @Test
    fun `동시에 전송하는 이벤트 수는 max concurrency를 넘지 않는다`() {
        val dispatchService = Mockito.mock(OutboxDispatchService::class.java)
        val events = (1L..2L).map(::claimed)
        val active = AtomicInteger()
        val maxObserved = AtomicInteger()
        Mockito.`when`(dispatchService.claimBatch(2)).thenReturn(events)
        events.forEach { event ->
            Mockito.`when`(dispatchService.send(event)).thenAnswer {
                Mono.defer {
                    val current = active.incrementAndGet()
                    maxObserved.updateAndGet { previous -> maxOf(previous, current) }
                    Mono.delay(Duration.ofMillis(40))
                        .doOnNext { active.decrementAndGet() }
                        .then()
                }
            }
            Mockito.`when`(dispatchService.markSent(event)).thenReturn(true)
        }

        val processed = worker(dispatchService, batchSize = 5, maxConcurrency = 2)
            .processOnce()

        assertEquals(2, processed)
        assertEquals(2, maxObserved.get())
        assertEquals(0, active.get())
        events.forEach { event -> Mockito.verify(dispatchService).markSent(event) }
    }

    private fun worker(
        dispatchService: OutboxDispatchService,
        batchSize: Int,
        maxConcurrency: Int
    ): OutboxWorker {
        return OutboxWorker(
            outboxDispatchService = dispatchService,
            batchSize = batchSize,
            maxConcurrency = maxConcurrency
        )
    }

    private fun claimed(id: Long): ClaimedOutboxEvent {
        return ClaimedOutboxEvent(
            id = id,
            idempotencyKey = "idempotency-key-$id",
            aggregateId = "ai-room-$id",
            payload = "{}",
            attemptCount = 1,
            lockToken = "lock-token-$id"
        )
    }
}
