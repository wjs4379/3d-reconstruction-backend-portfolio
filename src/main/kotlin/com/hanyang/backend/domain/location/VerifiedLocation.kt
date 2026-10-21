package com.hanyang.backend.domain.location

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "verified_locations", indexes = [Index(name = "idx_location_owner_expiry", columnList = "owner_user_id,expires_at")])
class VerifiedLocation(
    @Id @Column(name = "location_id", length = 36) val id: String = UUID.randomUUID().toString(),
    @Column(name = "owner_user_id", nullable = false) val ownerUserId: Long,
    @Column(name = "canonical_address", nullable = false, length = 255) val canonicalAddress: String,
    @Column(name = "road_address", length = 255) val roadAddress: String? = null,
    @Column(name = "jibun_address", length = 255) val jibunAddress: String? = null,
    @Column(name = "postal_code", length = 10) val postalCode: String? = null,
    @Column(name = "region_code", nullable = false, length = 20) val regionCode: String,
    @Column(name = "region_name", nullable = false, length = 100) val regionName: String,
    @Column(nullable = false, precision = 10, scale = 7) val latitude: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 7) val longitude: BigDecimal,
    @Column(nullable = false, length = 20) val provider: String = "KAKAO",
    @Column(name = "verified_at", nullable = false) val verifiedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "expires_at", nullable = false) val expiresAt: LocalDateTime = LocalDateTime.now().plusMinutes(15)
)
