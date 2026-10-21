package com.hanyang.backend.domain.listing

import com.hanyang.backend.domain.unit.BuildingType
import com.hanyang.backend.domain.unit.FacingDirection
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/listings/map")
class ListingMapController(private val service: ListingMapService) {
    @GetMapping fun search(@RequestParam south: Double, @RequestParam west: Double, @RequestParam north: Double,
        @RequestParam east: Double, @RequestParam(defaultValue = "200") limit: Int,
        @RequestParam(required = false) regionCode: String?,
        @RequestParam(required = false) minDepositWon: Long?, @RequestParam(required = false) maxDepositWon: Long?,
        @RequestParam(required = false) minMonthlyRentWon: Long?, @RequestParam(required = false) maxMonthlyRentWon: Long?,
        @RequestParam(required = false) dealType: DealType?,
        @RequestParam(required = false) minSalePriceWon: Long?, @RequestParam(required = false) maxSalePriceWon: Long?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) buildingType: BuildingType?, @RequestParam(required = false) direction: FacingDirection?,
        @RequestParam(required = false) minAreaSqm: BigDecimal?, @RequestParam(required = false) maxAreaSqm: BigDecimal?,
        @RequestParam(required = false) minRoomCount: Int?, @RequestParam(required = false) hasElevator: Boolean?,
        @RequestParam(required = false) hasParking: Boolean?, @RequestParam(required = false) petsAllowed: Boolean?,
        @RequestParam(required = false) furnished: Boolean?, @RequestParam(required = false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) availableBy: LocalDate?) = service.search(south, west, north, east, limit, regionCode, minDepositWon,
            maxDepositWon, minMonthlyRentWon, maxMonthlyRentWon, dealType, minSalePriceWon, maxSalePriceWon, q,
        ListingDetailsFilter(buildingType,direction,minAreaSqm,maxAreaSqm,minRoomCount,hasElevator,hasParking,petsAllowed,furnished,availableBy))
}
