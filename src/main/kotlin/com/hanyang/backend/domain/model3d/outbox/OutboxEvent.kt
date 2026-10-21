package com.hanyang.backend.domain.model3d.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

enum class OutboxEventType {
    AI_ANALYZE,
    AI_CORRECTION_RECONSTRUCT
}

enum class OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    DEAD
}

@Entity
@Table(
    name = "outbox_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_outbox_event_aggregate_type",
            columnNames = ["aggregate_id", "event_type"]
        )
    ],
    indexes = [
        Index(
            name = "idx_outbox_event_ready",
            columnList = "status,next_attempt_at,id"
        ),
        Index(
            name = "idx_outbox_event_lease",
            columnList = "status,locked_until,id"
        )
    ]
)
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    val idempotencyKey: String = UUID.randomUUID().toString(),

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_type", nullable = false, length = 40)
    val eventType: OutboxEventType,

    @Column(name = "aggregate_id", nullable = false, length = 64)
    val aggregateId: String,

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: LocalDateTime,

    @Column(name = "lock_token", length = 64)
    var lockToken: String? = null,

    @Column(name = "locked_until")
    var lockedUntil: LocalDateTime? = null,

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,

    @Column(name = "sent_at")
    var sentAt: LocalDateTime? = null
)
