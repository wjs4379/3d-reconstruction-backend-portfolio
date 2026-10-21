package com.hanyang.backend.domain.listing

import com.hanyang.backend.domain.unit.BuildingType
import com.hanyang.backend.domain.unit.FacingDirection
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class ListingController(private val service: ListingService) {
    @PostMapping("/units/{unitId}/listings", "/units/{unitId}/listing")
    fun create(@PathVariable unitId: Long, @Valid @RequestBody request: ListingCreateRequest) = service.create(unitId, request)
    @GetMapping("/units/{unitId}/listings") fun allAtUnit(@PathVariable unitId: Long) = service.allAtUnit(unitId)
    @GetMapping("/units/{unitId}/listing") fun atUnit(@PathVariable unitId: Long) = service.atUnit(unitId)
    @GetMapping("/me/listings") fun mine(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = service.mine(page, size)
    @RequestMapping("/listings/{listingId}", method = [RequestMethod.PUT, RequestMethod.PATCH])
    fun update(@PathVariable listingId: Long, @Valid @RequestBody request: ListingUpdateRequest) = service.update(listingId, request)
    @PostMapping("/listings/{listingId}/publish") fun publish(@PathVariable listingId: Long, @Valid @RequestBody request: ListingVersionRequest) = service.publish(listingId, request)
    @PostMapping("/listings/{listingId}/hide") fun hide(@PathVariable listingId: Long, @Valid @RequestBody request: ListingVersionRequest) = service.hide(listingId, request)
    @PostMapping("/listings/{listingId}/complete") fun complete(@PathVariable listingId: Long, @Valid @RequestBody request: ListingVersionRequest) = service.complete(listingId,request)
    @PostMapping("/listings/{listingId}/reopen") fun reopen(@PathVariable listingId: Long, @Valid @RequestBody request: ListingVersionRequest) = service.reopen(listingId,request)
    @GetMapping("/listings/{listingId}/draft") fun draft(@PathVariable listingId: Long) = service.draft(listingId)
    @GetMapping("/listings/{listingId}") fun detail(@PathVariable listingId: Long) = service.publicDetail(listingId)
    @GetMapping("/listings") fun search(
        @RequestParam(required = false) regionCode: String?,
        @RequestParam(required = false) minDepositWon: Long?, @RequestParam(required = false) maxDepositWon: Long?,
        @RequestParam(required = false) minMonthlyRentWon: Long?, @RequestParam(required = false) maxMonthlyRentWon: Long?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) dealType: DealType?,
        @RequestParam(required = false) minSalePriceWon: Long?, @RequestParam(required = false) maxSalePriceWon: Long?,
        @RequestParam(defaultValue = "ALL") locationFilter: LocationFilter, @RequestParam(required = false) q: String?,
        @RequestParam(required = false) buildingType: BuildingType?, @RequestParam(required = false) direction: FacingDirection?,
        @RequestParam(required = false) minAreaSqm: BigDecimal?, @RequestParam(required = false) maxAreaSqm: BigDecimal?,
        @RequestParam(required = false) minRoomCount: Int?, @RequestParam(required = false) hasElevator: Boolean?,
        @RequestParam(required = false) hasParking: Boolean?, @RequestParam(required = false) petsAllowed: Boolean?,
        @RequestParam(required = false) furnished: Boolean?, @RequestParam(required = false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) availableBy: LocalDate?,
        @RequestParam(defaultValue = "RECENT") sort: ListingSort
    ) = service.search(regionCode, minDepositWon, maxDepositWon, minMonthlyRentWon, maxMonthlyRentWon, page, size, dealType, minSalePriceWon, maxSalePriceWon, locationFilter, q,
        ListingDetailsFilter(buildingType,direction,minAreaSqm,maxAreaSqm,minRoomCount,hasElevator,hasParking,petsAllowed,furnished,availableBy),sort)
}
