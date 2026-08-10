package com.hanyang.backend.domain.model3d.outbox

import com.hanyang.backend.domain.model3d.AiAnalyzeRequestDto
import com.hanyang.backend.domain.model3d.AiServerClient
import com.hanyang.backend.domain.model3d.ModelStatus
import com.hanyang.backend.domain.model3d.Room3DModelRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.math.min

data class ClaimedOutboxEvent(
    val id: Long,
    val idempotencyKey: String,
    val aggregateId: String,
    val payload: String,
    val attemptCount: Int,
    val lockToken: String
)

enum class DispatchFailureOutcome {
    RETRY_SCHEDULED,
    DEAD,
    IGNORED_STALE_RESULT
}

@Service
class OutboxDispatchService(
    private val outboxEventRepository: OutboxEventRepository,
    private val room3DModelRepository: Room3DModelRepository,
    private val aiServerClient: AiServerClient,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
    @param:Value("\${outbox.worker.lease-ms:30000}")
    private val leaseMs: Long,
    @param:Value("\${outbox.worker.request-timeout-ms:10000}")
    private val requestTimeoutMs: Long,
    @param:Value("\${outbox.worker.max-attempts:5}")
    private val maxAttempts: Int,
    @param:Value("\${outbox.worker.base-backoff-ms:1000}")
    private val baseBackoffMs: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 처리할 행을 짧은 DB 트랜잭션에서 선점합니다. HTTP 호출 중에는 이 락을 유지하지 않습니다.
     */
    @Transactional
    fun claimBatch(batchSize: Int): List<ClaimedOutboxEvent> {
        if (batchSize <= 0) {
            return emptyList()
        }

        val readyAt = LocalDateTime.now(clock)
        val events = outboxEventRepository.findReadyForUpdate(
            pendingStatus = OutboxStatus.PENDING,
            processingStatus = OutboxStatus.PROCESSING,
            now = readyAt,
            pageable = PageRequest.of(0, batchSize)
        )
        val claimedAt = LocalDateTime.now(clock)

        return events.map { event ->
            val token = UUID.randomUUID().toString()
            event.status = OutboxStatus.PROCESSING
            event.attemptCount += 1
            event.lockToken = token
            event.lockedUntil = claimedAt.plusNanos(leaseMs * 1_000_000)
            event.updatedAt = claimedAt

            ClaimedOutboxEvent(
                id = requireNotNull(event.id),
                idempotencyKey = event.idempotencyKey,
                aggregateId = event.aggregateId,
                payload = event.payload,
                attemptCount = event.attemptCount,
                lockToken = token
            )
        }
    }

    /** AI HTTP 호출은 DB 트랜잭션 밖에서 실행됩니다. */
    fun send(claimed: ClaimedOutboxEvent): Mono<Void> {
        return Mono.defer {
            val request = jsonMapper.readValue(
                claimed.payload,
                AiAnalyzeRequestDto::class.java
            )
            aiServerClient.analyze(request, claimed.idempotencyKey)
        }.timeout(Duration.ofMillis(requestTimeoutMs))
    }

    @Transactional
    fun markSent(claimed: ClaimedOutboxEvent): Boolean {
        val updated = outboxEventRepository.markSentIfOwned(
            eventId = claimed.id,
            lockToken = claimed.lockToken,
            processingStatus = OutboxStatus.PROCESSING,
            sentStatus = OutboxStatus.SENT,
            now = LocalDateTime.now(clock)
        )
        return updated == 1
    }

    @Transactional
    fun markFailure(
        claimed: ClaimedOutboxEvent,
        error: Throwable
    ): DispatchFailureOutcome {
        val now = LocalDateTime.now(clock)
        val unwrapped = Exceptions.unwrap(error)
        val lastError = describe(unwrapped)
        val retryable = isRetryable(unwrapped)
        val exhausted = claimed.attemptCount >= maxAttempts

        if (retryable && !exhausted) {
            val updated = outboxEventRepository.scheduleRetryIfOwned(
                eventId = claimed.id,
                lockToken = claimed.lockToken,
                processingStatus = OutboxStatus.PROCESSING,
                pendingStatus = OutboxStatus.PENDING,
                nextAttemptAt = now.plusNanos(backoffMs(claimed.attemptCount) * 1_000_000),
                lastError = lastError,
                now = now
            )
            return if (updated == 1) {
                DispatchFailureOutcome.RETRY_SCHEDULED
            } else {
                DispatchFailureOutcome.IGNORED_STALE_RESULT
            }
        }

        val updated = outboxEventRepository.markDeadIfOwned(
            eventId = claimed.id,
            lockToken = claimed.lockToken,
            processingStatus = OutboxStatus.PROCESSING,
            deadStatus = OutboxStatus.DEAD,
            lastError = lastError,
            now = now
        )
        if (updated == 0) {
            return DispatchFailureOutcome.IGNORED_STALE_RESULT
        }

        val errorRoomData = runCatching {
            jsonMapper.writeValueAsString(
                mapOf(
                    "code" to "AI_REQUEST_FAILED",
                    "operation" to "analyze",
                    "message" to lastError
                )
            )
        }.getOrElse {
            "{\"code\":\"AI_REQUEST_FAILED\",\"operation\":\"analyze\"}"
        }

        room3DModelRepository.markFailedIfCurrentStatus(
            aiRoomId = claimed.aggregateId,
            expectedStatus = ModelStatus.ANALYZING,
            failedStatus = ModelStatus.FAILED,
            errorRoomData = errorRoomData
        )
        log.error(
            "Outbox AI request permanently failed. eventId={}, aiRoomId={}, attempts={}, retryable={}",
            claimed.id,
            claimed.aggregateId,
            claimed.attemptCount,
            retryable,
            unwrapped
        )
        return DispatchFailureOutcome.DEAD
    }

    private fun isRetryable(error: Throwable): Boolean {
        return when (error) {
            is WebClientResponseException -> {
                val status = error.statusCode.value()
                status == 408 || status == 429 || status >= 500
            }
            is WebClientRequestException,
            is TimeoutException -> true
            else -> error.cause?.let(::isRetryable) ?: false
        }
    }

    private fun backoffMs(attemptCount: Int): Long {
        val exponent = min((attemptCount - 1).coerceAtLeast(0), 20)
        val multiplier = 1L shl exponent
        return runCatching { Math.multiplyExact(baseBackoffMs, multiplier) }
            .getOrDefault(Long.MAX_VALUE / 1_000_000)
            .coerceAtMost(Duration.ofHours(1).toMillis())
    }

    private fun describe(error: Throwable): String {
        val type = error::class.simpleName ?: "Throwable"
        val message = error.message?.replace(Regex("\\s+"), " ")?.trim()
        return (if (message.isNullOrEmpty()) type else "$type: $message").take(2000)
    }
}
