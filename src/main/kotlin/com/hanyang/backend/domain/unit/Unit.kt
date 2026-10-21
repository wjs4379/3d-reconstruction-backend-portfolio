package com.hanyang.backend.domain.unit

import com.hanyang.backend.domain.property.Property
import jakarta.persistence.*
import com.hanyang.backend.domain.location.VerifiedLocation
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

enum class LocationVisibility { HIDDEN, BUILDING }

@Entity
@Table(name = "units", indexes = [Index(name = "idx_units_creator", columnList = "creator_user_id")])
class Unit(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "unit_id") val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "property_id", nullable = false) val property: Property,
    @Column(name = "creator_user_id", nullable = false) val creatorUserId: Long,
    @Column(nullable = false, length = 100) var name: String,
    @Column(name = "region_code", nullable = false, length = 20) var regionCode: String,
    @Column(name = "public_address", length = 255) var publicAddress: String? = null,
    var floor: Int? = null,
    @Column(name = "area_sqm", precision = 10, scale = 2) var areaSqm: BigDecimal? = null,
    @Column(name = "available_from") var availableFrom: LocalDate? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "location_id") var location: VerifiedLocation? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "location_visibility", nullable = false, length = 20)
    var locationVisibility: LocationVisibility = LocationVisibility.HIDDEN,
    @Version @Column(nullable = false) var version: Long = 0,
    @Column(name = "updated_at", nullable = false) var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
    @Embedded var details: UnitDetails? = null
)
