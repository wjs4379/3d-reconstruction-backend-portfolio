package com.hanyang.backend.domain.usage

import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "room_usages")
class RoomUsage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usage_id")
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User, // 빌린 사람(Guest)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: Room, // 빌린 방

    @Column(nullable = false)
    var status: String, // ACTIVE(사용중), COMPLETED(종료)

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime,

    @Column(name = "ended_at", nullable = false)
    var endedAt: LocalDateTime
)