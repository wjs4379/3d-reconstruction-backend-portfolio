package com.hanyang.backend.domain.unit

import jakarta.persistence.*
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class BuildingType { APARTMENT, OFFICETEL, VILLA, HOUSE, COMMERCIAL, OTHER }
enum class FacingDirection { EAST, WEST, SOUTH, NORTH, NORTHEAST, NORTHWEST, SOUTHEAST, SOUTHWEST }

/** Publisher-declared details. Null means unknown, not false or zero; no verified survey is implied. */
@Embeddable
data class UnitDetails(
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="building_type",length=20)
    val buildingType: BuildingType? = null,
    @field:Min(1) @field:Max(200) @Column(name="total_floors") val totalFloors: Int? = null,
    @field:Min(0) @field:Max(99) @Column(name="room_count") val roomCount: Int? = null,
    @field:Min(0) @field:Max(99) @Column(name="bathroom_count") val bathroomCount: Int? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="facing_direction",length=20)
    val direction: FacingDirection? = null,
    @Column(name="has_elevator") val hasElevator: Boolean? = null,
    @Column(name="has_parking") val hasParking: Boolean? = null,
    @Column(name="pets_allowed") val petsAllowed: Boolean? = null,
    @Column(name="furnished") val furnished: Boolean? = null
)
