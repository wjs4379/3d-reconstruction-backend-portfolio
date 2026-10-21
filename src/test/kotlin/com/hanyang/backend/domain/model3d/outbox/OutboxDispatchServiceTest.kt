package com.hanyang.backend.domain.model3d.outbox

import com.hanyang.backend.domain.model3d.AiAnalyzeRequestDto
import com.hanyang.backend.domain.model3d.AiCorrectionReconstructRequestDto
import com.hanyang.backend.domain.model3d.CorrectionRequestDto
import com.hanyang.backend.domain.model3d.VertexDto
import com.hanyang.backend.domain.model3d.AiServerClient
import com.hanyang.backend.domain.model3d.ModelStatus
import com.hanyang.backend.domain.model3d.Room3DModelRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeoutException

class OutboxDispatchServiceTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val clock = Clock.fixed(
        Instant.parse("2026-08-10T03:00:00Z"),
        ZoneId.of("Asia/Seoul")
    )
    private val now = LocalDateTime.now(clock)

    @Test
    fun `만료된 processing 이벤트도 새로운 lease로 다시 선점한다`() {
        val fixture = fixture()
        val expired = event(
            id = 1L,
            status = OutboxStatus.PROCESSING,
            attemptCount = 1,
            lockToken = "old-token",
            lockedUntil = now.minusSeconds(1)
        )
        Mockito.`when`(
            fixture.outboxRepository.findReadyForUpdate(
                OutboxStatus.PENDING,
                OutboxStatus.PROCESSING,
                now,
                PageRequest.of(0, 4)
            )
        ).thenReturn(listOf(expired))

        val claimed = fixture.service.claimBatch(4).single()

        assertEquals(OutboxStatus.PROCESSING, expired.status)
        assertEquals(2, claimed.attemptCount)
        assertNotEquals("old-token", claimed.lockToken)
        assertEquals(now.plusSeconds(30), expired.lockedUntil)
    }

    @Test
    fun `일시적인 전송 실패는 첫 번째 백오프 뒤 재시도 상태로 돌린다`() {
        val fixture = fixture()
        val claimed = claim(attemptCount = 1)
        Mockito.`when`(
            fixture.outboxRepository.scheduleRetryIfOwned(
                eventId = claimed.id,
                lockToken = claimed.lockToken,
                processingStatus = OutboxStatus.PROCESSING,
                pendingStatus = OutboxStatus.PENDING,
                nextAttemptAt = now.plusSeconds(1),
                lastError = "TimeoutException: fake timeout",
                now = now
            )
        ).thenReturn(1)

        val result = fixture.service.markFailure(
            claimed,
            TimeoutException("fake timeout")
        )

        assertEquals(DispatchFailureOutcome.RETRY_SCHEDULED, result)
        Mockito.verifyNoInteractions(fixture.modelRepository)
    }

    @Test
    fun `재시도할 수 없는 오류는 DEAD로 만들고 분석 중인 모델만 실패 처리한다`() {
        val fixture = fixture()
        val claimed = claim(attemptCount = 1)
        Mockito.`when`(
            fixture.outboxRepository.markDeadIfOwned(
                eventId = claimed.id,
                lockToken = claimed.lockToken,
                processingStatus = OutboxStatus.PROCESSING,
                deadStatus = OutboxStatus.DEAD,
                lastError = "IllegalArgumentException: invalid payload",
                now = now
            )
        ).thenReturn(1)

        val result = fixture.service.markFailure(
            claimed,
            IllegalArgumentException("invalid payload")
        )

        assertEquals(DispatchFailureOutcome.DEAD, result)
        val expectedErrorRoomData = jsonMapper.writeValueAsString(
            mapOf(
                "code" to "AI_REQUEST_FAILED",
                "operation" to "analyze",
                "message" to "IllegalArgumentException: invalid payload"
            )
        )
        Mockito.verify(fixture.modelRepository).markFailedIfCurrentStatus(
            claimed.aggregateId,
            ModelStatus.ANALYZING,
            ModelStatus.FAILED,
            expectedErrorRoomData
        )
    }

    @Test
    fun `이전 Worker의 늦은 성공은 lock token이 달라 반영되지 않는다`() {
        val fixture = fixture()
        val claimed = claim(attemptCount = 1)
        Mockito.`when`(
            fixture.outboxRepository.markSentIfOwned(
                claimed.id,
                claimed.lockToken,
                OutboxStatus.PROCESSING,
                OutboxStatus.SENT,
                now
            )
        ).thenReturn(0)

        val updated = fixture.service.markSent(claimed)

        assertEquals(false, updated)
    }

    @Test
    fun `AI 요청에는 Outbox에 저장한 payload와 동일한 멱등성 키를 사용한다`() {
        val fixture = fixture()
        val claimed = claim(attemptCount = 2)
        val expectedRequest = jsonMapper.readValue(
            claimed.payload,
            AiAnalyzeRequestDto::class.java
        )
        Mockito.`when`(
            fixture.aiServerClient.analyze(
                expectedRequest,
                claimed.idempotencyKey
            )
        ).thenReturn(Mono.empty())

        fixture.service.send(claimed).block()

        Mockito.verify(fixture.aiServerClient).analyze(
            expectedRequest,
            claimed.idempotencyKey
        )
    }

    @Test
    fun `correction command replays both steps unchanged after an ambiguous HTTP acknowledgement`() {
        val fixture=fixture()
        val request=AiCorrectionReconstructRequestDto("ai-room-1",CorrectionRequestDto(listOf(VertexDto("v",1.0,2.0,0.0)),emptyList()))
        val claimed=claim(1).copy(eventType=OutboxEventType.AI_CORRECTION_RECONSTRUCT,payload=jsonMapper.writeValueAsString(request))
        Mockito.`when`(fixture.aiServerClient.sendCorrection(request.aiRoomId,request.correction,"${claimed.idempotencyKey}:correction")).thenReturn(Mono.empty())
        Mockito.`when`(fixture.aiServerClient.reconstruct(request.aiRoomId,"${claimed.idempotencyKey}:reconstruct"))
            .thenReturn(Mono.error(TimeoutException("lost HTTP ACK")),Mono.empty())
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) { fixture.service.send(claimed).block() }
        fixture.service.send(claimed.copy(attemptCount=2,lockToken="replacement-worker")).block()
        Mockito.verify(fixture.aiServerClient,Mockito.times(2)).sendCorrection(request.aiRoomId,request.correction,"${claimed.idempotencyKey}:correction")
        Mockito.verify(fixture.aiServerClient,Mockito.times(2)).reconstruct(request.aiRoomId,"${claimed.idempotencyKey}:reconstruct")
    }
    @Test
    fun `permanent correction delivery failure affects only reconstructing models`() {
        val fixture=fixture();val claimed=claim(5).copy(eventType=OutboxEventType.AI_CORRECTION_RECONSTRUCT)
        Mockito.`when`(fixture.outboxRepository.markDeadIfOwned(claimed.id,claimed.lockToken,OutboxStatus.PROCESSING,OutboxStatus.DEAD,"TimeoutException: unavailable",now)).thenReturn(1)
        assertEquals(DispatchFailureOutcome.DEAD,fixture.service.markFailure(claimed,TimeoutException("unavailable")))
        val error=jsonMapper.writeValueAsString(mapOf("code" to "AI_REQUEST_FAILED","operation" to "correction-and-reconstruct","message" to "TimeoutException: unavailable"))
        Mockito.verify(fixture.modelRepository).markFailedIfCurrentStatus(claimed.aggregateId,ModelStatus.RECONSTRUCTING,ModelStatus.FAILED,error)
    }
    private fun fixture(): Fixture {
        val outboxRepository = Mockito.mock(OutboxEventRepository::class.java)
        val modelRepository = Mockito.mock(Room3DModelRepository::class.java)
        val aiServerClient = Mockito.mock(AiServerClient::class.java)
        return Fixture(
            outboxRepository,
            modelRepository,
            aiServerClient,
            OutboxDispatchService(
                outboxEventRepository = outboxRepository,
                room3DModelRepository = modelRepository,
                aiServerClient = aiServerClient,
                jsonMapper = jsonMapper,
                clock = clock,
                leaseMs = 30_000,
                requestTimeoutMs = 10_000,
                maxAttempts = 5,
                baseBackoffMs = 1_000
            )
        )
    }

    private fun event(
        id: Long,
        status: OutboxStatus,
        attemptCount: Int,
        lockToken: String? = null,
        lockedUntil: LocalDateTime? = null
    ): OutboxEvent {
        return OutboxEvent(
            id = id,
            idempotencyKey = "event-key-$id",
            eventType = OutboxEventType.AI_ANALYZE,
            aggregateId = "ai-room-$id",
            payload = payload("ai-room-$id"),
            status = status,
            attemptCount = attemptCount,
            nextAttemptAt = now.minusSeconds(1),
            lockToken = lockToken,
            lockedUntil = lockedUntil,
            createdAt = now.minusMinutes(1),
            updatedAt = now.minusMinutes(1)
        )
    }

    private fun claim(attemptCount: Int): ClaimedOutboxEvent {
        return ClaimedOutboxEvent(
            id = 1L,
            idempotencyKey = "stable-idempotency-key",
            aggregateId = "ai-room-1",
            payload = payload("ai-room-1"),
            attemptCount = attemptCount,
            lockToken = "current-token"
        )
    }

    private fun payload(aiRoomId: String): String {
        return jsonMapper.writeValueAsString(
            AiAnalyzeRequestDto(
                imagePaths = listOf("https://example.com/room.jpg"),
                roomId = aiRoomId,
                roomName = "Test Room",
                userId = "00000000-0000-0000-0000-000000000001",
                webhookUrl = "http://localhost:8080/api/webhook"
            )
        )
    }

    private data class Fixture(
        val outboxRepository: OutboxEventRepository,
        val modelRepository: Room3DModelRepository,
        val aiServerClient: AiServerClient,
        val service: OutboxDispatchService
    )
}
