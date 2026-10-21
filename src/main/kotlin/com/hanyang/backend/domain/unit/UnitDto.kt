package com.hanyang.backend.domain.unit

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

// publicAddress is the publisher's explicitly chosen public address, never copied from the private Property address.
data class UnitCreateRequest(
    @field:Positive @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val propertyId: Long,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Pattern(regexp = "[A-Za-z0-9_-]{2,20}") val regionCode: String,
    @field:Size(max = 255) val publicAddress: String? = null,
    @field:Min(-20) @field:Max(200) val floor: Int? = null,
    @field:DecimalMin("0.01") @field:DecimalMax("99999999.99") @field:Digits(integer = 8, fraction = 2) val areaSqm: BigDecimal? = null,
    val availableFrom: LocalDate? = null,
    @field:Size(max = 36) val locationToken: String? = null,
    val locationVisibility: LocationVisibility = LocationVisibility.HIDDEN,
    @field:Valid val details: UnitDetails = UnitDetails()
)
data class PropertyUnitCreateRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Pattern(regexp = "[A-Za-z0-9_-]{2,20}") val regionCode: String,
    @field:Size(max = 255) val publicAddress: String? = null,
    @field:Min(-20) @field:Max(200) val floor: Int? = null,
    @field:DecimalMin("0.01") @field:DecimalMax("99999999.99") @field:Digits(integer = 8, fraction = 2) val areaSqm: BigDecimal? = null,
    val availableFrom: LocalDate? = null,
    @field:Size(max = 36) val locationToken: String? = null,
    val locationVisibility: LocationVisibility = LocationVisibility.HIDDEN,
    @field:Valid val details: UnitDetails = UnitDetails()
) {
    fun withProperty(id: Long) = UnitCreateRequest(id, name, regionCode, publicAddress, floor, areaSqm, availableFrom, locationToken, locationVisibility, details)
}
data class UnitView(
    val id: Long, val propertyId: Long, val propertyTitle: String, val name: String,
    val regionCode: String, val publicAddress: String?, val floor: Int?, val areaSqm: BigDecimal?,
    val availableFrom: LocalDate?, val createdAt: LocalDateTime, val version: Long,
    val locationToken: String?, val locationVisibility: LocationVisibility, val locationVerified: Boolean,
    val canonicalAddress: String?, val postalCode: String?, val regionName: String?, val latitude: Double?, val longitude: Double?, val details: UnitDetails = UnitDetails()
) {
    companion object {
        fun from(unit: Unit) = UnitView(requireNotNull(unit.id), requireNotNull(unit.property.id), unit.property.title,
            unit.name, unit.regionCode, unit.publicAddress, unit.floor, unit.areaSqm, unit.availableFrom, unit.createdAt, unit.version, unit.location?.id, unit.locationVisibility, unit.location != null,
            unit.location?.canonicalAddress, unit.location?.postalCode, unit.location?.regionName,
            unit.location?.latitude?.toDouble(), unit.location?.longitude?.toDouble(), unit.details ?: UnitDetails())
    }
}
data class SpaceCreateRequest(@field:NotBlank @field:Size(max = 100) val name: String)
data class SpaceView(val id: Long, val unitId: Long, val name: String)

data class UnitUpdateRequest(
    @field:Min(0) @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Pattern(regexp = "[A-Za-z0-9_-]{2,20}") val regionCode: String,
    @field:Size(max = 255) val publicAddress: String? = null,
    @field:Min(-20) @field:Max(200) val floor: Int? = null,
    @field:DecimalMin("0.01") @field:DecimalMax("99999999.99") @field:Digits(integer = 8, fraction = 2) val areaSqm: BigDecimal? = null,
    val availableFrom: LocalDate? = null,
    @field:Size(max = 36) val locationToken: String? = null,
    val locationVisibility: LocationVisibility = LocationVisibility.HIDDEN,
    @field:Valid val details: UnitDetails = UnitDetails()
)
