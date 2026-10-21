package com.hanyang.backend.domain.property

import jakarta.persistence.*
import com.hanyang.backend.domain.location.VerifiedLocation
import java.time.LocalDateTime

@Entity
@Table(name = "properties")
class Property(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "property_id")
    val id: Long? = null,

    @Column(nullable = false)
    var title: String, // 건물명

    @Column(columnDefinition = "TEXT")
    var description: String? = null, // 건물 설명

    @Column(nullable = false)
    var address: String, // 위치

    @Column(name = "creator_user_id")
    val creatorUserId: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    val location: VerifiedLocation? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)