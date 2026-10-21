package com.hanyang.backend.domain.listing

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.common.PageView
import com.hanyang.backend.common.pageRequest
import com.hanyang.backend.domain.media.MediaAssetRepository
import com.hanyang.backend.domain.media.MediaAsset
import com.hanyang.backend.domain.media.MediaKind
import com.hanyang.backend.domain.media.MediaStatus
import com.hanyang.backend.domain.model3d.ModelStatus
import com.hanyang.backend.domain.model3d.Room3DModelRepository
import com.hanyang.backend.domain.unit.UnitRepository
import com.hanyang.backend.security.CurrentUser
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.PageImpl
import com.hanyang.backend.domain.unit.UnitDetails
import java.math.BigDecimal
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ListingService(
    private val listings: ListingRepository, private val units: UnitRepository,
    private val assets: MediaAssetRepository, private val models: Room3DModelRepository,
    private val currentUser: CurrentUser, private val entityManager: EntityManager
) {
    @Transactional
    fun create(unitId: Long, request: ListingCreateRequest): ListingView {
        val unit = units.findForUpdate(unitId) ?: throw ApiException(HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "호실을 찾을 수 없습니다.")
        if (unit.creatorUserId != currentUser.id()) forbidden()
        if (listings.findByUnitIdAndDealType(unitId, request.dealType) != null) throw ApiException(HttpStatus.CONFLICT, "LISTING_ALREADY_EXISTS", "이 호실에 같은 거래 유형의 매물이 이미 있습니다.")
        validateValues(unitId, request)
        val selections = validateModels(unitId, request)
        val listing = Listing(unit = unit, dealType = request.dealType, title = request.title.trim(), description = request.description?.trim(),
            deposit = request.depositWon, monthlyRent = request.monthlyRentWon, managementFee = request.managementFeeWon,
            managementFeeIncludes = request.managementFeeIncludes?.trim(), representativeAssetId = request.representativeAssetId,
            photoAssetIds = request.photoAssetIds.toMutableList(), modelAssetId = selections.firstOrNull()?.modelAssetId,
            selectedModelId = selections.firstOrNull()?.selectedModelId, spaceId = selections.firstOrNull()?.spaceId,
            salePrice = request.salePriceWon, modelSelections = selections.toMutableList())
        return ListingView.from(listings.saveAndFlush(listing), managed = true)
    }
    @Transactional
    fun update(id: Long, request: ListingUpdateRequest): ListingView {
        val listing = requireManagedListingForUpdate(id, request.expectedVersion)
        requireOpenListing(listing)
        if (request.dealType != listing.dealType) throw ApiException(HttpStatus.BAD_REQUEST, "DEAL_TYPE_IMMUTABLE", "거래 유형이 다르면 새 매물을 등록해 주세요.")
        validateValues(requireNotNull(listing.unit.id), request.values())
        val selections = validateModels(requireNotNull(listing.unit.id), request.values())
        if (listing.status == ListingStatus.PUBLISHED && request.representativeAssetId == null) throw ApiException(HttpStatus.BAD_REQUEST, "REPRESENTATIVE_REQUIRED", "공개 매물에는 대표 사진이 필요합니다.")
        listing.title = request.title.trim(); listing.description = request.description?.trim()
        listing.deposit = request.depositWon; listing.monthlyRent = request.monthlyRentWon
        listing.managementFee = request.managementFeeWon; listing.managementFeeIncludes = request.managementFeeIncludes?.trim()
        listing.representativeAssetId = request.representativeAssetId
        listing.photoAssetIds.clear(); listing.photoAssetIds.addAll(request.photoAssetIds)
        listing.modelSelections.clear(); listing.modelSelections.addAll(selections)
        listing.modelAssetId = selections.firstOrNull()?.modelAssetId; listing.selectedModelId = selections.firstOrNull()?.selectedModelId; listing.spaceId = selections.firstOrNull()?.spaceId
        listing.salePrice = request.salePriceWon
        listing.updatedAt = LocalDateTime.now()
        return ListingView.from(listings.saveAndFlush(listing), managed = true)
    }
    @Transactional
    fun publish(id: Long, request: ListingVersionRequest): ListingView {
        val listing = requireManagedListingForUpdate(id, request.expectedVersion)
        requireOpenListing(listing)
        validateValues(requireNotNull(listing.unit.id), values(listing))
        if (listing.representativeAssetId == null) throw ApiException(HttpStatus.BAD_REQUEST, "REPRESENTATIVE_REQUIRED", "매물을 공개하려면 검증된 대표 사진이 필요합니다.")
        listing.status = ListingStatus.PUBLISHED
        listing.publishedAt = LocalDateTime.now(); listing.updatedAt = LocalDateTime.now()
        return ListingView.from(listings.saveAndFlush(listing), managed = true)
    }
    @Transactional
    fun hide(id: Long, request: ListingVersionRequest): ListingView {
        val listing = requireManagedListingForUpdate(id, request.expectedVersion)
        requireOpenListing(listing)
        listing.status = ListingStatus.HIDDEN; listing.updatedAt = LocalDateTime.now()
        return ListingView.from(listings.saveAndFlush(listing), managed = true)
    }
    @Transactional
    fun complete(id: Long, request: ListingVersionRequest): ListingView {
        val listing = requireManagedListingForUpdate(id, request.expectedVersion)
        if (listing.status !in setOf(ListingStatus.PUBLISHED,ListingStatus.HIDDEN)) throw ApiException(HttpStatus.CONFLICT,
            "INVALID_LISTING_TRANSITION","공개 또는 숨김 매물만 모집 종료할 수 있습니다.")
        listing.status = ListingStatus.COMPLETED
        listing.completedAt = LocalDateTime.now(); listing.updatedAt = LocalDateTime.now()
        return ListingView.from(listings.saveAndFlush(listing),managed=true)
    }
    @Transactional
    fun reopen(id: Long, request: ListingVersionRequest): ListingView {
        val listing = requireManagedListingForUpdate(id, request.expectedVersion)
        if (listing.status != ListingStatus.COMPLETED) throw ApiException(HttpStatus.CONFLICT,
            "INVALID_LISTING_TRANSITION","모집 종료 매물만 다시 초안으로 열 수 있습니다.")
        listing.status = ListingStatus.DRAFT
        listing.completedAt = null; listing.publishedAt = null; listing.updatedAt = LocalDateTime.now()
        return ListingView.from(listings.saveAndFlush(listing),managed=true)
    }
    private fun requireOpenListing(listing: Listing) {
        if (listing.status == ListingStatus.COMPLETED) throw ApiException(HttpStatus.CONFLICT,
            "LISTING_COMPLETED","모집 종료 매물은 먼저 다시 초안으로 열어 주세요.")
    }
    @Transactional(readOnly = true)
    fun draft(id: Long): ListingView {
        val listing = listings.findById(id).orElseThrow { notFound() }
        if (listing.unit.creatorUserId != currentUser.id()) forbidden()
        return ListingView.from(listing, managed = true)
    }
    @Transactional(readOnly = true)
    fun atUnit(unitId: Long): ListingView {
        val unit = units.findById(unitId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "호실을 찾을 수 없습니다.") }
        if (unit.creatorUserId != currentUser.id()) forbidden()
        return ListingView.from(listings.findByUnitIdAndDealType(unitId, DealType.MONTHLY_RENT) ?: throw notFound(), managed = true)
    }
    @Transactional(readOnly = true)
    fun allAtUnit(unitId: Long): List<ListingView> {
        val unit = units.findById(unitId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "호실을 찾을 수 없습니다.") }
        if (unit.creatorUserId != currentUser.id()) forbidden()
        return listings.findByUnitIdOrderByIdAsc(unitId).map { ListingView.from(it, managed = true) }
    }
    @Transactional(readOnly = true)
    fun mine(page: Int, size: Int) = PageView.from(listings.findByUnitCreatorUserId(currentUser.id(), pageRequest(page, size)).map { ListingView.from(it, managed = true) })
    @Transactional(readOnly = true)
    fun publicDetail(id: Long): ListingView {
        val listing = listings.findById(id).orElseThrow { notFound() }
        if (listing.status != ListingStatus.PUBLISHED) throw notFound()
        return ListingView.from(listing)
    }
    @Transactional(readOnly = true)
    fun search(regionCode: String?, minDepositWon: Long?, maxDepositWon: Long?, minMonthlyRentWon: Long?, maxMonthlyRentWon: Long?, page: Int, size: Int,
        dealType: DealType? = null, minSalePriceWon: Long? = null, maxSalePriceWon: Long? = null,
        locationFilter: LocationFilter = LocationFilter.ALL, q: String? = null,
        details: ListingDetailsFilter = ListingDetailsFilter(), sort: ListingSort = ListingSort.RECENT): PageView<ListingView> {
        val paging = pageRequest(page,size)
        if (sort in setOf(ListingSort.PRICE_ASC,ListingSort.PRICE_DESC) && dealType == null) throw ApiException(HttpStatus.BAD_REQUEST,
            "SORT_DEAL_TYPE_REQUIRED","가격 정렬에는 월세·전세·매매 유형을 선택해 주세요.")
        val spec = filterSpecification(regionCode,minDepositWon,maxDepositWon,minMonthlyRentWon,maxMonthlyRentWon,
            dealType,minSalePriceWon,maxSalePriceWon,locationFilter,q,details)
        val cb = entityManager.criteriaBuilder
        val query = cb.createQuery(Listing::class.java)
        val root = query.from(Listing::class.java)
        query.select(root).where(spec.toPredicate(root,query,cb))
        val orders = when(sort) {
            ListingSort.RECENT -> listOf(cb.desc(root.get<java.time.LocalDateTime>("publishedAt")))
            ListingSort.PRICE_ASC,ListingSort.PRICE_DESC -> {
                val field = when(dealType) { DealType.MONTHLY_RENT -> "monthlyRent"; DealType.JEONSE -> "deposit"; DealType.SALE -> "salePrice"; null -> error("Validated deal type is missing") }
                val price = root.get<Long>(field)
                listOf(if(sort==ListingSort.PRICE_ASC) cb.asc(price) else cb.desc(price))
            }
            ListingSort.AREA_ASC,ListingSort.AREA_DESC -> {
                val area = root.get<com.hanyang.backend.domain.unit.Unit>("unit").get<BigDecimal>("areaSqm")
                // Explicit CASE works on both MySQL and H2; nullable areas always appear last.
                listOf(cb.asc(cb.selectCase<Int>().`when`(cb.isNull(area),1).otherwise(0)),
                    if(sort==ListingSort.AREA_ASC) cb.asc(area) else cb.desc(area))
            }
        }
        query.orderBy(orders+cb.desc(root.get<Long>("id")))
        val rows = entityManager.createQuery(query).setFirstResult(paging.offset.toInt()).setMaxResults(size).resultList
        val countQuery = cb.createQuery(Long::class.javaObjectType)
        val countRoot = countQuery.from(Listing::class.java)
        countQuery.select(cb.count(countRoot)).where(spec.toPredicate(countRoot,countQuery,cb))
        val total = entityManager.createQuery(countQuery).singleResult.toLong()
        return PageView.from(PageImpl(rows.map { ListingView.from(it) },paging,total))
    }
    internal fun filterSpecification(regionCode: String?, minDepositWon: Long?, maxDepositWon: Long?, minMonthlyRentWon: Long?, maxMonthlyRentWon: Long?,
        dealType: DealType?, minSalePriceWon: Long?, maxSalePriceWon: Long?, locationFilter: LocationFilter, q: String?,
        details: ListingDetailsFilter = ListingDetailsFilter()): Specification<Listing> {
        validateDetailFilters(details)
        checkRange(minDepositWon, maxDepositWon); checkRange(minMonthlyRentWon, maxMonthlyRentWon); checkRange(minSalePriceWon, maxSalePriceWon)
        if (regionCode != null && !Regex("[A-Za-z0-9_-]{2,20}").matches(regionCode)) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REGION", "지역 코드를 확인해 주세요.")
        if ((q?.length ?: 0) > 100) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_QUERY", "검색어는 100자 이하로 입력해 주세요.")
        return Specification { root, _, cb ->
            val unit = root.join<Listing, com.hanyang.backend.domain.unit.Unit>("unit")
            val location = unit.join<com.hanyang.backend.domain.unit.Unit, com.hanyang.backend.domain.location.VerifiedLocation>("location", jakarta.persistence.criteria.JoinType.LEFT)
            val predicates = mutableListOf<Predicate>(cb.equal(root.get<ListingStatus>("status"), ListingStatus.PUBLISHED))
            if (regionCode != null) predicates.add(cb.equal(unit.get<String>("regionCode"), regionCode))
            if (dealType != null) predicates.add(cb.equal(root.get<DealType>("dealType"), dealType))
            if (minDepositWon != null) predicates.add(cb.ge(root.get<Long>("deposit"), minDepositWon))
            if (maxDepositWon != null) predicates.add(cb.le(root.get<Long>("deposit"), maxDepositWon))
            if (minMonthlyRentWon != null) predicates.add(cb.ge(root.get<Long>("monthlyRent"), minMonthlyRentWon))
            if (maxMonthlyRentWon != null) predicates.add(cb.le(root.get<Long>("monthlyRent"), maxMonthlyRentWon))
            if (minSalePriceWon != null) predicates.add(cb.ge(root.get<Long>("salePrice"), minSalePriceWon))
            if (maxSalePriceWon != null) predicates.add(cb.le(root.get<Long>("salePrice"), maxSalePriceWon))
            val declared = unit.get<UnitDetails>("details")
            details.buildingType?.let { predicates.add(cb.equal(declared.get<com.hanyang.backend.domain.unit.BuildingType>("buildingType"),it)) }
            details.direction?.let { predicates.add(cb.equal(declared.get<com.hanyang.backend.domain.unit.FacingDirection>("direction"),it)) }
            details.minAreaSqm?.let { predicates.add(cb.ge(unit.get<BigDecimal>("areaSqm"),it)) }
            details.maxAreaSqm?.let { predicates.add(cb.le(unit.get<BigDecimal>("areaSqm"),it)) }
            details.minRoomCount?.let { predicates.add(cb.ge(declared.get<Int>("roomCount"),it)) }
            details.hasElevator?.let { predicates.add(cb.equal(declared.get<Boolean>("hasElevator"),it)) }
            details.hasParking?.let { predicates.add(cb.equal(declared.get<Boolean>("hasParking"),it)) }
            details.petsAllowed?.let { predicates.add(cb.equal(declared.get<Boolean>("petsAllowed"),it)) }
            details.furnished?.let { predicates.add(cb.equal(declared.get<Boolean>("furnished"),it)) }
            details.availableBy?.let { predicates.add(cb.lessThanOrEqualTo(unit.get<java.time.LocalDate>("availableFrom"),it)) }
            val mapped = cb.and(cb.equal(unit.get<com.hanyang.backend.domain.unit.LocationVisibility>("locationVisibility"), com.hanyang.backend.domain.unit.LocationVisibility.BUILDING), cb.isNotNull(location.get<String>("id")))
            if (locationFilter == LocationFilter.MAPPED) predicates.add(mapped)
            if (locationFilter == LocationFilter.UNMAPPED) predicates.add(cb.not(mapped))
            q?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val pattern = "%" + it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                predicates.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(unit.get("publicAddress")), pattern, '\\'), cb.like(cb.lower(location.get("regionName")), pattern, '\\')))
            }
            cb.and(*predicates.toTypedArray())
        }
    }
    // All commands and inquiry snapshots acquire the same listing guard row.
    fun requireManagedListingForUpdate(id: Long, expectedVersion: Long): Listing {
        val listing = listings.findForUpdate(id) ?: throw notFound()
        if (listing.unit.creatorUserId != currentUser.id()) forbidden()
        checkVersion(listing, expectedVersion)
        return listing
    }
    fun checkVersion(listing: Listing, expectedVersion: Long) {
        if (listing.version != expectedVersion) throw ApiException(HttpStatus.CONFLICT, "LISTING_VERSION_CONFLICT", "매물이 변경되었습니다. 새로고침 후 다시 시도해 주세요.")
    }
    private fun validateValues(unitId: Long, request: ListingCreateRequest) {
        validateTerms(request)
        if (request.title.isBlank() || request.title.length > 200 || (request.description?.length ?: 0) > 10000 ||
            (request.managementFeeWon != null && request.managementFeeWon !in 0..1000000000L) ||
            (request.managementFeeIncludes?.length ?: 0) > 500) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_LISTING", "매물 제목과 거래 조건을 확인해 주세요.")
        if (request.photoAssetIds.size > 20 || request.photoAssetIds.distinct().size != request.photoAssetIds.size || request.photoAssetIds.any { it <= 0 }) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTOS", "사진은 중복 없이 최대 20개까지 등록할 수 있습니다.")
        request.photoAssetIds.forEach { requireAsset(it, unitId, MediaKind.IMAGE) }
        request.representativeAssetId?.let {
            requireAsset(it, unitId, MediaKind.IMAGE)
            if (it !in request.photoAssetIds) throw ApiException(HttpStatus.BAD_REQUEST, "REPRESENTATIVE_NOT_IN_PHOTOS", "대표 사진은 매물 사진 목록에 있어야 합니다.")
        }
        validateModels(unitId, request)
    }
    private fun validateTerms(request: ListingCreateRequest) {
        val valid = when (request.dealType) {
            DealType.MONTHLY_RENT -> request.depositWon != null && request.depositWon in 0..1000000000000L &&
                request.monthlyRentWon != null && request.monthlyRentWon in 1..1000000000000L && request.salePriceWon == null
            DealType.JEONSE -> request.depositWon != null && request.depositWon in 1..1000000000000L && request.monthlyRentWon == null && request.salePriceWon == null
            DealType.SALE -> request.salePriceWon != null && request.salePriceWon in 1..1000000000000L && request.depositWon == null && request.monthlyRentWon == null
        }
        if (!valid) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEAL_TERMS", "월세는 보증금·월세, 전세는 전세금, 매매는 매매가만 입력해 주세요.")
    }
    private fun validateModels(unitId: Long, request: ListingCreateRequest): List<ListingModelSelection> {
        val references = listOf(request.modelAssetId, request.selectedModelId, request.spaceId)
        val selected = request.modelSelections ?: if (references.all { it == null }) emptyList() else {
            if (references.any { it == null }) throw ApiException(HttpStatus.BAD_REQUEST, "INCOMPLETE_MODEL_REFERENCE", "모델 파일·모델·공간을 함께 선택해 주세요.")
            listOf(ModelSelectionRequest(requireNotNull(request.spaceId), requireNotNull(request.modelAssetId), requireNotNull(request.selectedModelId)))
        }
        if (selected.size > 20 || selected.map { it.spaceId }.distinct().size != selected.size) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODEL_SELECTIONS", "공간마다 하나의 완료 모델을 최대 20개까지 선택해 주세요.")
        return selected.map { selection ->
            val asset = requireAsset(selection.modelAssetId, unitId, MediaKind.MODEL)
            val model = models.findById(selection.selectedModelId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "모델을 찾을 수 없습니다.") }
            if (model.status != ModelStatus.SUCCESS || model.room.id != selection.spaceId || model.room.unit?.id != unitId || model.room.owner.id != currentUser.id()) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PUBLIC_MODEL", "이 호실의 완료 모델만 연결할 수 있습니다.")
            if (asset.sourceModelId != model.id) throw ApiException(HttpStatus.BAD_REQUEST, "MODEL_ASSET_MISMATCH", "선택한 모델과 검증된 파일이 일치하지 않습니다.")
            ListingModelSelection(selection.spaceId, selection.modelAssetId, selection.selectedModelId, model.room.roomName)
        }
    }
    private fun requireAsset(id: Long, unitId: Long, kind: MediaKind): MediaAsset {
        val asset = assets.findById(id).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "파일을 찾을 수 없습니다.") }
        if (asset.unitId != unitId || asset.ownerUserId != currentUser.id()) throw ApiException(HttpStatus.FORBIDDEN, "ASSET_FORBIDDEN", "이 호실의 본인 파일만 사용할 수 있습니다.")
        if (asset.status != MediaStatus.VERIFIED || asset.kind != kind) throw ApiException(HttpStatus.BAD_REQUEST, "ASSET_NOT_VERIFIED", "검증된 형식의 파일만 사용할 수 있습니다.")
        return asset
    }
    private fun values(l: Listing) = ListingCreateRequest(l.title, l.description, l.deposit, l.monthlyRent, l.managementFee,
        l.managementFeeIncludes, l.representativeAssetId, l.photoAssetIds, l.modelAssetId, l.selectedModelId, l.spaceId, l.dealType, l.salePrice,
        l.modelSelections.map { ModelSelectionRequest(it.spaceId, it.modelAssetId, it.selectedModelId) })
    private fun validateDetailFilters(filter: ListingDetailsFilter) {
        val lower = filter.minAreaSqm; val upper = filter.maxAreaSqm
        if (listOfNotNull(lower,upper).any { it <= BigDecimal.ZERO || it > BigDecimal("99999999.99") } ||
            (lower != null && upper != null && lower > upper) || (filter.minRoomCount != null && filter.minRoomCount !in 0..99)) {
            throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_DETAIL_FILTER","면적과 최소 방 수 검색 범위를 확인해 주세요.")
        }
    }
    private fun checkRange(min: Long?, max: Long?) {
        if ((min != null && min !in 0..1000000000000L) || (max != null && max !in 0..1000000000000L) || (min != null && max != null && min > max)) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "금액 검색 범위를 확인해 주세요.")
    }
    private fun forbidden(): Nothing = throw ApiException(HttpStatus.FORBIDDEN, "LISTING_FORBIDDEN", "매물 관리 권한이 없습니다.")
    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "공개된 매물을 찾을 수 없습니다.")
}
