package com.hanyang.backend.domain.listing

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.*
import jakarta.validation.Valid
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ListingCreateRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:Size(max = 10000) val description: String? = null,
    @field:Min(0) @field:Max(1000000000000) val depositWon: Long? = null,
    @field:Min(1) @field:Max(1000000000000) val monthlyRentWon: Long? = null,
    @field:Min(0) @field:Max(1000000000) val managementFeeWon: Long? = null,
    @field:Size(max = 500) val managementFeeIncludes: String? = null,
    val representativeAssetId: Long? = null,
    @field:Size(max = 20) val photoAssetIds: List<Long> = emptyList(),
    val modelAssetId: Long? = null,
    @param:JsonAlias("modelId") val selectedModelId: Long? = null,
    val spaceId: Long? = null,
    val dealType: DealType = DealType.MONTHLY_RENT,
    @field:Min(1) @field:Max(1000000000000) val salePriceWon: Long? = null,
    @field:Size(max = 20) @field:Valid val modelSelections: List<ModelSelectionRequest>? = null
)
data class ListingUpdateRequest(
    @field:Min(0) @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:Size(max = 10000) val description: String? = null,
    @field:Min(0) @field:Max(1000000000000) val depositWon: Long? = null,
    @field:Min(1) @field:Max(1000000000000) val monthlyRentWon: Long? = null,
    @field:Min(0) @field:Max(1000000000) val managementFeeWon: Long? = null,
    @field:Size(max = 500) val managementFeeIncludes: String? = null,
    val representativeAssetId: Long? = null,
    @field:Size(max = 20) val photoAssetIds: List<Long> = emptyList(),
    val modelAssetId: Long? = null,
    @param:JsonAlias("modelId") val selectedModelId: Long? = null,
    val spaceId: Long? = null,
    val dealType: DealType = DealType.MONTHLY_RENT,
    @field:Min(1) @field:Max(1000000000000) val salePriceWon: Long? = null,
    @field:Size(max = 20) @field:Valid val modelSelections: List<ModelSelectionRequest>? = null
) {
    fun values() = ListingCreateRequest(title, description, depositWon, monthlyRentWon, managementFeeWon,
        managementFeeIncludes, representativeAssetId, photoAssetIds, modelAssetId, selectedModelId, spaceId, dealType, salePriceWon, modelSelections)
}
data class ListingVersionRequest(
    @field:Min(0) @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val expectedVersion: Long
)
data class ListingView(
    val id: Long, val unitId: Long, val dealType: DealType, val status: ListingStatus,
    val title: String, val description: String?, val depositWon: Long?, val monthlyRentWon: Long?,
    val managementFeeWon: Long?, val managementFeeIncludes: String?, val representativeAssetId: Long?,
    val photoAssetIds: List<Long>, val modelAssetId: Long?, val selectedModelId: Long?, val spaceId: Long?,
    val version: Long, val regionCode: String, val publicAddress: String?, val unitName: String?,
    val areaSqm: BigDecimal?, val availableFrom: LocalDate?, val publishedAt: LocalDateTime?, val updatedAt: LocalDateTime,
    val salePriceWon: Long?, val modelSelections: List<ListingModelSelection>,
    val locationVerified: Boolean, val locationVisibility: com.hanyang.backend.domain.unit.LocationVisibility,
    val canonicalAddress: String?, val postalCode: String?, val regionName: String?, val latitude: Double?, val longitude: Double?,
    val floor: Int? = null, val details: com.hanyang.backend.domain.unit.UnitDetails = com.hanyang.backend.domain.unit.UnitDetails(),
    val completedAt: LocalDateTime? = null
) {
    companion object {
        fun from(listing: Listing, managed: Boolean = false): ListingView {
            val location = listing.unit.location
            val publishLocation = listing.unit.locationVisibility == com.hanyang.backend.domain.unit.LocationVisibility.BUILDING && location != null
            return ListingView(requireNotNull(listing.id), requireNotNull(listing.unit.id), listing.dealType,
                listing.status, listing.title, listing.description, listing.deposit, listing.monthlyRent,
                listing.managementFee, listing.managementFeeIncludes, listing.representativeAssetId,
                listing.photoAssetIds.toList(), listing.modelAssetId, listing.selectedModelId, listing.spaceId,
                listing.version, listing.unit.regionCode, listing.unit.publicAddress, listing.unit.name.takeIf { managed },
                listing.unit.areaSqm, listing.unit.availableFrom, listing.publishedAt, listing.updatedAt,
                listing.salePrice, listing.modelSelections.toList(), location != null, listing.unit.locationVisibility,
                location?.canonicalAddress?.takeIf { publishLocation }, location?.postalCode?.takeIf { publishLocation },
                location?.regionName, location?.latitude?.toDouble()?.takeIf { publishLocation }, location?.longitude?.toDouble()?.takeIf { publishLocation },
                listing.unit.floor, listing.unit.details ?: com.hanyang.backend.domain.unit.UnitDetails(), listing.completedAt)
        }
    }
}
data class ModelSelectionRequest(
    @field:Positive @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val spaceId: Long,
    @field:Positive @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val modelAssetId: Long,
    @field:Positive @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val selectedModelId: Long
)
enum class LocationFilter { ALL, MAPPED, UNMAPPED }
