package com.hanyang.backend.domain.listing

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.location.VerifiedLocation
import com.hanyang.backend.domain.unit.Unit
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Join
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class MapListingPoint(
    val id: Long, val unitId: Long, val dealType: DealType, val title: String,
    val depositWon: Long?, val monthlyRentWon: Long?, val salePriceWon: Long?, val representativeAssetId: Long?,
    val latitude: BigDecimal, val longitude: BigDecimal, val publicAddress: String?, val areaSqm: BigDecimal?
)
data class ListingMapView(val content: List<MapListingPoint>, val hasMore: Boolean, val limit: Int)

@Service
class ListingMapService(private val entityManager: EntityManager, private val listingService: ListingService) {
    @Transactional(readOnly = true)
    fun search(south: Double, west: Double, north: Double, east: Double, limit: Int,
        regionCode: String?, minDepositWon: Long?, maxDepositWon: Long?, minMonthlyRentWon: Long?, maxMonthlyRentWon: Long?,
        dealType: DealType?, minSalePriceWon: Long?, maxSalePriceWon: Long?, q: String?,
        details: ListingDetailsFilter = ListingDetailsFilter()): ListingMapView {
        if (listOf(south, west, north, east).any { !it.isFinite() } || south !in 32.0..40.0 || north !in 32.0..40.0 ||
            west !in 123.0..133.0 || east !in 123.0..133.0 || south >= north || west >= east || limit !in 1..200) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAP_BOUNDS", "대한민국 지도 범위와 limit(1~200)을 확인해 주세요.")
        }
        if (north - south > 1.000000001 || east - west > 1.000000001) throw ApiException(HttpStatus.BAD_REQUEST,
            "MAP_VIEWPORT_TOO_BROAD", "지도를 확대해 주세요. 한 번에 위도·경도 각각 1도 이내에서 조회할 수 있습니다.")
        val cb = entityManager.criteriaBuilder
        val query = cb.createQuery(MapListingPoint::class.java)
        val root = query.from(Listing::class.java)
        val filter = listingService.filterSpecification(regionCode, minDepositWon, maxDepositWon, minMonthlyRentWon,
            maxMonthlyRentWon, dealType, minSalePriceWon, maxSalePriceWon, LocationFilter.MAPPED, q, details).toPredicate(root, query, cb)
        @Suppress("UNCHECKED_CAST")
        val unit = root.joins.first { it.attribute.name == "unit" } as Join<Listing, Unit>
        @Suppress("UNCHECKED_CAST")
        val location = unit.joins.first { it.attribute.name == "location" } as Join<Unit, VerifiedLocation>
        query.select(cb.construct(MapListingPoint::class.java, root.get<Long>("id"), unit.get<Long>("id"), root.get<DealType>("dealType"),
            root.get<String>("title"), root.get<Long>("deposit"), root.get<Long>("monthlyRent"), root.get<Long>("salePrice"),
            root.get<Long>("representativeAssetId"), location.get<BigDecimal>("latitude"), location.get<BigDecimal>("longitude"),
            unit.get<String>("publicAddress"), unit.get<BigDecimal>("areaSqm")))
        query.where(cb.and(filter, cb.between(location.get("latitude"), BigDecimal.valueOf(south), BigDecimal.valueOf(north)),
            cb.between(location.get("longitude"), BigDecimal.valueOf(west), BigDecimal.valueOf(east))))
        query.orderBy(cb.desc(root.get<java.time.LocalDateTime>("publishedAt")), cb.desc(root.get<Long>("id")))
        // A projection query and limit+1 avoid loading photos/models or running a global count.
        val rows = entityManager.createQuery(query).setMaxResults(limit + 1).resultList
        return ListingMapView(rows.take(limit), rows.size > limit, limit)
    }
}
