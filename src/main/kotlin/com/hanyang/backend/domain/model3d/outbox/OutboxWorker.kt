package com.hanyang.backend.domain.model3d.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Component
@ConditionalOnProperty(
    prefix = "outbox",
    name = ["enabled", "worker.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class OutboxWorker(
    private val outboxDispatchService: OutboxDispatchService,
    @param:Value("\${outbox.worker.batch-size:4}")
    private val batchSize: Int,
    @param:Value("\${outbox.worker.max-concurrency:4}")
    private val maxConcurrency: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${outbox.worker.fixed-delay-ms:500}")
    fun dispatchScheduled() {
        processOnce()
    }

    /** 테스트와 수동 검증에서 한 번의 Worker 주기를 명시적으로 실행할 수 있습니다. */
    fun processOnce(): Int {
        val concurrency = maxConcurrency.coerceAtLeast(1)
        val effectiveBatchSize = batchSize.coerceAtLeast(1).coerceAtMost(concurrency)
        val claimed = outboxDispatchService.claimBatch(effectiveBatchSize)
        if (claimed.isEmpty()) {
            return 0
        }

        Flux.fromIterable(claimed)
            .flatMap(
                { event -> dispatchOne(event) },
                concurrency
            )
            .blockLast()

        return claimed.size
    }

    private fun dispatchOne(event: ClaimedOutboxEvent): Mono<Void> {
        return outboxDispatchService.send(event)
            .thenReturn(true)
            .onErrorResume { error ->
                Mono.fromCallable {
                    val outcome = outboxDispatchService.markFailure(event, error)
                    log.warn(
                        "Outbox dispatch failed. eventId={}, attempt={}, outcome={}",
                        event.id,
                        event.attemptCount,
                        outcome
                    )
                }.subscribeOn(Schedulers.boundedElastic()).thenReturn(false)
            }
            .flatMap { sent ->
                if (!sent) {
                    return@flatMap Mono.empty<Void>()
                }

                Mono.fromCallable {
                    val updated = outboxDispatchService.markSent(event)
                    if (!updated) {
                        log.info(
                            "Ignoring stale Outbox success. eventId={}, lockToken={}",
                            event.id,
                            event.lockToken
                        )
                    }
                }
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume { error ->
                        log.error(
                            "Outbox acknowledgement failed; leaving event for lease recovery. eventId={}, attempt={}",
                            event.id,
                            event.attemptCount,
                            error
                        )
                        Mono.empty()
                    }
                    .then()
            }
            .then()
    }
}
