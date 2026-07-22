package com.hanyang.backend.domain.property

import jakarta.persistence.*
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

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)