package com.hanyang.backend.domain.room

import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.unit.Unit
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "rooms")
class Room(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    var property: Property, // 소속 건물

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    var owner: User, // 방 소유자(Host)

    @Column(name = "room_name", nullable = false)
    var roomName: String, // 방 이름

    @Column(name = "price_per_night")
    var pricePerNight: Int? = null, // 1박 가격

    @Column(name = "max_guests")
    var maxGuests: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    var unit: Unit? = null, // 수용 인원

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)