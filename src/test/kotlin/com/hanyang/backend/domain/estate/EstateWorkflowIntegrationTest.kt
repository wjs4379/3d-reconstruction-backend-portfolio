package com.hanyang.backend.domain.estate

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.inquiry.*
import com.hanyang.backend.domain.location.*
import com.hanyang.backend.domain.favorite.*
import com.hanyang.backend.domain.listing.*
import com.hanyang.backend.domain.media.*
import com.hanyang.backend.domain.model3d.*
import com.hanyang.backend.domain.property.*
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.unit.*
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import com.hanyang.backend.security.AuthenticatedUser
import com.hanyang.backend.security.CurrentUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/** H2 verifies the service contract and persistence, not MySQL concurrent locking semantics. */
@DataJpaTest
@Import(UnitService::class, ListingService::class, InquiryService::class, PropertyV2Service::class, LocationService::class, ListingMapService::class, FavoriteService::class, CurrentUser::class)
class EstateWorkflowIntegrationTest @Autowired constructor(
    private val users: UserRepository, private val properties: PropertyRepository,
    private val unitService: UnitService, private val propertyService: PropertyV2Service,
    private val listingService: ListingService, private val inquiryService: InquiryService,
    private val assets: MediaAssetRepository, private val rooms: RoomRepository,
    private val models: Room3DModelRepository, private val entityManager: EntityManager,
    private val locationService: LocationService, private val locations: LocationRepository,
    private val mapService: ListingMapService, private val favoriteService: FavoriteService
) {
    @MockitoBean private lateinit var locationProvider: LocationProvider
    private var ownerId = 0L
    private var visitorId = 0L
    private var strangerId = 0L
    private var unitId = 0L
    private var assetId = 0L

    @BeforeEach fun setUp() {
        ownerId = user("owner"); visitorId = user("visitor"); strangerId = user("stranger")
        asUser(ownerId)
        val property = propertyService.create(PropertyCreateRequest("서울 빌딩", "비공개 상세 주소 301호"))
        unitId = unitService.create(UnitCreateRequest(property.id, "301호", "SEOUL", "서울 성동구")).id
        assetId = image(unitId, ownerId)
    }
    @AfterEach fun clearPrincipal() = SecurityContextHolder.clearContext()

    @Test fun `호실 공간은 숙박 가격 없이 생성하고 관리자가 서버에서 정해진다`() {
        val space = unitService.createSpace(unitId, SpaceCreateRequest("거실"))
        entityManager.flush(); entityManager.clear()
        val saved = rooms.findById(space.id).orElseThrow()
        assertNull(saved.pricePerNight); assertNull(saved.maxGuests)
        assertEquals(ownerId, saved.owner.id); assertEquals(unitId, saved.unit?.id)
        asUser(visitorId)
        assertStatus(HttpStatus.FORBIDDEN) { unitService.createSpace(unitId, SpaceCreateRequest("침실")) }
        assertStatus(HttpStatus.FORBIDDEN) { unitService.get(unitId) }
    }

    @Test fun `미분류 기존 건물의 관리자를 자동으로 지정하지 않는다`() {
        val legacy = properties.saveAndFlush(Property(title = "기존 건물", address = "기존 주소"))
        assertStatus(HttpStatus.FORBIDDEN) { unitService.create(UnitCreateRequest(requireNotNull(legacy.id), "401호", "SEOUL")) }
        assertNull(legacy.creatorUserId)
    }

    @Test fun `공개에는 검증된 대표 사진이 필요하고 초안과 숨김은 조회에서 제외된다`() {
        val draft = listingService.create(unitId, values(photos = false))
        assertStatus(HttpStatus.NOT_FOUND) { listingService.publicDetail(draft.id) }
        assertTrue(search().content.isEmpty())
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.publish(draft.id, ListingVersionRequest(draft.version)) }
        val ready = listingService.update(draft.id, update(draft, values()))
        val published = listingService.publish(draft.id, ListingVersionRequest(ready.version))
        assertEquals(published.id, search().content.single().id)
        assertEquals("서울 성동구", listingService.publicDetail(draft.id).publicAddress)
        listingService.hide(draft.id, ListingVersionRequest(published.version))
        assertTrue(search().content.isEmpty())
        assertStatus(HttpStatus.NOT_FOUND) { listingService.publicDetail(draft.id) }
    }

    @Test fun `월세 조건은 Long으로 저장하고 관리비 미정과 무료를 구분한다`() {
        val draft = listingService.create(unitId, values(deposit = 3_000_000_000L))
        entityManager.flush(); entityManager.clear()
        val persisted = listingService.draft(draft.id)
        assertEquals(3_000_000_000L, persisted.depositWon)
        assertNull(persisted.managementFeeWon)
        val free = listingService.update(draft.id, update(persisted, values(deposit = persisted.depositWon).copy(managementFeeWon = 0)))
        assertEquals(0L, free.managementFeeWon)
        assertStatus(HttpStatus.CONFLICT) { listingService.create(unitId, values()) }
    }

    @Test fun `다른 게시자는 매물 수정과 초안 조회를 할 수 없다`() {
        val draft = listingService.create(unitId, values())
        asUser(strangerId)
        assertStatus(HttpStatus.FORBIDDEN) { listingService.draft(draft.id) }
        assertStatus(HttpStatus.FORBIDDEN) { listingService.update(draft.id, update(draft, values(title = "탈취"))) }
        assertStatus(HttpStatus.FORBIDDEN) { listingService.publish(draft.id, ListingVersionRequest(draft.version)) }
    }

    @Test fun `사진 변경도 버전을 올리고 오래된 공개와 문의 요청을 거부한다`() {
        val draft = listingService.create(unitId, values())
        val second = image(unitId, ownerId)
        val changed = listingService.update(draft.id, update(draft, values().copy(photoAssetIds = listOf(assetId, second))))
        assertTrue(changed.version > draft.version)
        assertStatus(HttpStatus.CONFLICT) { listingService.publish(draft.id, ListingVersionRequest(draft.version)) }
        val published = listingService.publish(draft.id, ListingVersionRequest(changed.version))
        asUser(visitorId)
        assertStatus(HttpStatus.CONFLICT) { inquiryService.create(draft.id, InquiryCreateRequest(changed.version, "입주 가능일 문의")) }
        assertEquals(published.version, inquiryService.create(draft.id, InquiryCreateRequest(published.version, "입주 가능일 문의")).listingSnapshot.version)
    }

    @Test fun `문의 시점 조건은 이후 수정 숨김에도 보존되고 당사자만 응대한다`() {
        val draft = listingService.create(unitId, values())
        val published = listingService.publish(draft.id, ListingVersionRequest(draft.version))
        asUser(visitorId)
        val inquiry = inquiryService.create(draft.id, InquiryCreateRequest(published.version, "관리비 포함 항목이 궁금합니다"))
        asUser(ownerId)
        val updated = listingService.update(draft.id, update(published, values(title = "수정된 매물", deposit = 20_000_000L)))
        listingService.hide(draft.id, ListingVersionRequest(updated.version))
        val response = inquiryService.reply(inquiry.id, InquiryReplyRequest("관리비는 별도 협의입니다"))
        assertEquals(InquiryStatus.RESPONDED, response.status)
        assertEquals("301호 월세", response.listingSnapshot.title)
        assertEquals(10_000_000L, response.listingSnapshot.depositWon)
        assertEquals(published.version, response.listingSnapshot.version)
        assertNull(response.listingSnapshot.managementFeeWon)
        asUser(strangerId)
        assertStatus(HttpStatus.FORBIDDEN) { inquiryService.get(inquiry.id) }
        assertStatus(HttpStatus.FORBIDDEN) { inquiryService.received(unitId, 0, 20) }
        assertStatus(HttpStatus.FORBIDDEN) { inquiryService.reply(inquiry.id, InquiryReplyRequest("가짜 답변")) }
        asUser(visitorId)
        assertEquals(inquiry.id, inquiryService.mine(0, 20).content.single().id)
        assertStatus(HttpStatus.FORBIDDEN) { inquiryService.reply(inquiry.id, InquiryReplyRequest("본인 답변")) }
        assertEquals(InquiryStatus.CLOSED, inquiryService.close(inquiry.id).status)
        asUser(ownerId)
        assertStatus(HttpStatus.CONFLICT) { inquiryService.reply(inquiry.id, InquiryReplyRequest("종료 후 답변")) }
        asUser(visitorId)
        assertStatus(HttpStatus.NOT_FOUND) { inquiryService.create(draft.id, InquiryCreateRequest(updated.version, "숨김 이후 문의")) }
    }

    @Test fun `다른 호실 파일과 검증되지 않은 파일과 이미지가 아닌 파일은 사진으로 연결할 수 없다`() {
        val anotherProperty = propertyService.create(PropertyCreateRequest("다른 건물", "다른 주소"))
        val anotherUnit = unitService.create(UnitCreateRequest(anotherProperty.id, "401호", "SEOUL")).id
        val foreign = image(anotherUnit, ownerId)
        assertStatus(HttpStatus.FORBIDDEN) { listingService.create(unitId, values().copy(representativeAssetId = foreign, photoAssetIds = listOf(foreign))) }
        val staging = assets.saveAndFlush(MediaAsset(unitId = unitId, ownerUserId = ownerId, kind = MediaKind.MODEL,
            status = MediaStatus.VERIFIED, objectKey = "wrong-kind.glb", sizeBytes = 32, contentType = "model/gltf-binary"))
        val pendingId = requireNotNull(staging.id)
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.create(unitId, values().copy(representativeAssetId = pendingId, photoAssetIds = listOf(pendingId))) }
        val pending = assets.saveAndFlush(MediaAsset(unitId = unitId, ownerUserId = ownerId, kind = MediaKind.IMAGE,
            status = MediaStatus.PENDING, objectKey = "unverified.jpg", sizeBytes = 32, contentType = "image/jpeg"))
        val unverified = requireNotNull(pending.id)
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.create(unitId, values().copy(representativeAssetId = unverified, photoAssetIds = listOf(unverified))) }
    }

    @Test fun `명시적으로 선택한 완료 모델은 새 분석 실패에도 바뀌지 않는다`() {
        val space = unitService.createSpace(unitId, SpaceCreateRequest("거실"))
        val room = rooms.findById(space.id).orElseThrow()
        val completed = models.saveAndFlush(Room3DModel(room = room, status = ModelStatus.SUCCESS, modelUrl = "https://archive.example/complete.glb"))
        val modelAsset = assets.saveAndFlush(MediaAsset(unitId = unitId, ownerUserId = ownerId, kind = MediaKind.MODEL,
            status = MediaStatus.VERIFIED, objectKey = "fixed.glb", sizeBytes = 80, contentType = "model/gltf-binary", sourceModelId = completed.id))
        val input = values().copy(modelAssetId = modelAsset.id, selectedModelId = completed.id, spaceId = room.id)
        val draft = listingService.create(unitId, input)
        listingService.publish(draft.id, ListingVersionRequest(draft.version))
        models.saveAndFlush(Room3DModel(room = room, status = ModelStatus.FAILED))
        assertEquals(completed.id, listingService.publicDetail(draft.id).selectedModelId)
        assertEquals(modelAsset.id, listingService.publicDetail(draft.id).modelAssetId)
        val wrongAsset = assets.saveAndFlush(MediaAsset(unitId = unitId, ownerUserId = ownerId, kind = MediaKind.MODEL,
            status = MediaStatus.VERIFIED, objectKey = "unrelated.glb", sizeBytes = 80, contentType = "model/gltf-binary"))
        val current = listingService.draft(draft.id)
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.update(draft.id, update(current, input.copy(modelAssetId = wrongAsset.id))) }
    }

    @Test fun `검색 금액과 페이지 범위를 검증한다`() {
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.search(null, 100, 10, null, null, 0, 20) }
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.search(null, null, null, null, null, -1, 20) }
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.search(null, null, null, null, null, 0, 101) }
        val draft = listingService.create(unitId, values())
        listingService.publish(draft.id, ListingVersionRequest(draft.version))
        assertEquals(1L, listingService.search("SEOUL", 10_000_000L, 10_000_000L, 500_000L, 500_000L, 0, 20).totalElements)
        assertTrue(listingService.search("BUSAN", null, null, null, null, 0, 20).content.isEmpty())
    }


    @Test fun `외부 확인 결과는 서버가 저장한 토큰으로만 호실에 연결된다`() {
        val resolved = ResolvedAddress("서울 성동구 왕십리로 83", "서울 성동구 왕십리로 83", "서울 성동구 성수동1가 656-78", "04769",
            "1120011400", "서울 성동구 성수동1가", BigDecimal("37.5445000"), BigDecimal("127.0443000"))
        `when`(locationProvider.resolve("선택한 건물 주소")).thenReturn(resolved)
        val response = locationService.resolve(LocationResolveRequest(" 선택한 건물 주소 "))
        assertEquals(resolved.canonicalAddress, response.canonicalAddress)
        val stored = locationService.claim(response.locationToken)
        assertEquals(ownerId, stored.ownerUserId)
        assertEquals(resolved.longitude, stored.longitude)
        val property = propertyService.create(PropertyCreateRequest("건물", "비공개 상세 주소 301호", locationToken = response.locationToken))
        val unit = unitService.create(UnitCreateRequest(property.id, "301호", "MANUAL", locationToken = response.locationToken, locationVisibility = LocationVisibility.BUILDING))
        assertEquals("1120011400", unit.regionCode)
        assertEquals(resolved.canonicalAddress, unit.publicAddress)
    }

    @Test fun `좌표 토큰은 발급자에게 귀속되고 만료와 임의 식별자를 거부한다`() {
        val location = verifiedLocation()
        assertEquals(location.id, locationService.claim(location.id).id)
        asUser(visitorId)
        assertStatus(HttpStatus.FORBIDDEN) { locationService.claim(location.id) }
        asUser(ownerId)
        val expired = verifiedLocation(expiresAt = LocalDateTime.now().minusMinutes(1))
        assertStatus(HttpStatus.GONE) { locationService.claim(expired.id) }
        assertStatus(HttpStatus.BAD_REQUEST) { locationService.claim("made-up-coordinates") }
        asUser(visitorId)
        assertStatus(HttpStatus.FORBIDDEN) { propertyService.create(PropertyCreateRequest("외부 건물", "주소", locationToken = location.id)) }
    }

    @Test fun `확인된 주소도 명시적으로 건물 공개를 선택하기 전에는 좌표와 상세 호실명을 공개하지 않는다`() {
        val location = verifiedLocation()
        val property = propertyService.create(PropertyCreateRequest("건물", "비공개 원본 주소 301호", locationToken = location.id))
        val unit = unitService.create(UnitCreateRequest(property.id, "비공개 301호", "CLIENT", "성수동"))
        assertEquals(location.regionCode, unit.regionCode)
        assertEquals(LocationVisibility.HIDDEN, unit.locationVisibility)
        val photo = image(unit.id, ownerId)
        val draft = listingService.create(unit.id, values().copy(representativeAssetId = photo, photoAssetIds = listOf(photo)))
        val published = listingService.publish(draft.id, ListingVersionRequest(draft.version))
        val public = listingService.publicDetail(published.id)
        assertNull(public.latitude); assertNull(public.longitude); assertNull(public.canonicalAddress); assertNull(public.postalCode)
        assertNull(public.unitName); assertTrue(public.locationVerified)
        assertTrue(map().content.isEmpty())
        val changed = unitService.update(unit.id, UnitUpdateRequest(unit.version, unit.name, "IGNORED", "ignored", locationVisibility = LocationVisibility.BUILDING))
        assertTrue(changed.version > unit.version)
        val visible = listingService.publicDetail(published.id)
        assertEquals(location.canonicalAddress, visible.publicAddress)
        assertEquals(location.latitude.toDouble(), visible.latitude)
        assertEquals(location.postalCode, visible.postalCode)
        assertTrue(visible.version > published.version)
        assertEquals(published.id, map().content.single().id)
        assertStatus(HttpStatus.CONFLICT) { unitService.update(unit.id, UnitUpdateRequest(unit.version, unit.name, unit.regionCode)) }
        asUser(visitorId)
        assertStatus(HttpStatus.CONFLICT) { inquiryService.create(published.id, InquiryCreateRequest(published.version, "오래된 위치 조건")) }
    }

    @Test fun `수동 주소는 공개 목록에 있으나 지도에 없고 검증 없이 좌표 공개할 수 없다`() {
        val draft = listingService.create(unitId, values())
        listingService.publish(draft.id, ListingVersionRequest(draft.version))
        assertTrue(map().content.isEmpty())
        val unmapped = listingService.search(null, null, null, null, null, 0, 20, locationFilter = LocationFilter.UNMAPPED)
        assertEquals(draft.id, unmapped.content.single().id)
        assertTrue(listingService.search(null, null, null, null, null, 0, 20, locationFilter = LocationFilter.MAPPED).content.isEmpty())
        val unit = unitService.get(unitId)
        assertStatus(HttpStatus.BAD_REQUEST) { unitService.update(unitId, UnitUpdateRequest(unit.version, unit.name, unit.regionCode, locationVisibility = LocationVisibility.BUILDING)) }
    }

    @Test fun `지도는 게시된 건물만 제한된 투영으로 조회하고 검색어를 literal로 해석한다`() {
        val location = verifiedLocation()
        val property = propertyService.create(PropertyCreateRequest("성수 빌딩", "비공개 상세", locationToken = location.id))
        fun listing(name: String): ListingView {
            val unit = unitService.create(UnitCreateRequest(property.id, name, "IGNORED", locationVisibility = LocationVisibility.BUILDING))
            val photo = image(unit.id, ownerId)
            val draft = listingService.create(unit.id, values(title = name).copy(representativeAssetId = photo, photoAssetIds = listOf(photo)))
            return listingService.publish(draft.id, ListingVersionRequest(draft.version))
        }
        val first = listing("성수 % special"); val second = listing("성수 두번째")
        val one = map(limit = 1)
        assertEquals(1, one.content.size); assertTrue(one.hasMore); assertEquals(second.id, one.content.single().id)
        assertEquals(first.id, map(q = "%").content.single().id)
        assertEquals(2, map(q = "성동구").content.size)
        listingService.hide(second.id, ListingVersionRequest(second.version))
        assertEquals(first.id, map().content.single().id)
        assertStatus(HttpStatus.BAD_REQUEST) { mapService.search(32.0, 123.0, 40.0, 133.0, 200, null, null, null, null, null, null, null, null, null) }
        assertStatus(HttpStatus.BAD_REQUEST) { map(limit = 201) }
        assertStatus(HttpStatus.BAD_REQUEST) { mapService.search(Double.NaN, 127.0, 38.0, 128.0, 20, null, null, null, null, null, null, null, null, null) }
        assertTrue(mapService.search(36.0, 126.0, 36.5, 126.5, 20, null, null, null, null, null, null, null, null, null).content.isEmpty())
    }

    @Test fun `호실 정보 변경은 모든 거래 유형의 매물 버전을 함께 올린다`() {
        val monthly = listingService.create(unitId, values())
        val sale = listingService.create(unitId, values().copy(dealType = DealType.SALE, depositWon = null, monthlyRentWon = null, salePriceWon = 900_000_000))
        assertEquals(listOf(monthly.id, sale.id), listingService.allAtUnit(unitId).map { it.id })
        val before = unitService.get(unitId)
        unitService.update(unitId, UnitUpdateRequest(before.version, "새 이름", before.regionCode, "새 공개 주소"))
        assertTrue(listingService.draft(monthly.id).version > monthly.version)
        assertTrue(listingService.draft(sale.id).version > sale.version)
        assertEquals("새 공개 주소", listingService.draft(sale.id).publicAddress)
    }

    @Test fun `매매 전세 조건과 문의 시점 금액을 거래 유형에 맞게 보존한다`() {
        val saleTerms = values().copy(dealType = DealType.SALE, depositWon = null, monthlyRentWon = null, salePriceWon = 900_000_000)
        val sale = listingService.create(unitId, saleTerms)
        val published = listingService.publish(sale.id, ListingVersionRequest(sale.version))
        asUser(visitorId)
        val inquiry = inquiryService.create(sale.id, InquiryCreateRequest(published.version, "매매 문의"))
        assertEquals(DealType.SALE, inquiry.listingSnapshot.dealType)
        assertNull(inquiry.listingSnapshot.depositWon); assertNull(inquiry.listingSnapshot.monthlyRentWon)
        assertEquals(900_000_000L, inquiry.listingSnapshot.salePriceWon)
        asUser(ownerId)
        listingService.update(sale.id, update(published, saleTerms.copy(salePriceWon = 950_000_000)))
        assertEquals(900_000_000L, inquiryService.get(inquiry.id).listingSnapshot.salePriceWon)
        val jeonse = listingService.create(unitId, values().copy(dealType = DealType.JEONSE, monthlyRentWon = null, depositWon = 300_000_000))
        assertNull(jeonse.monthlyRentWon); assertNull(jeonse.salePriceWon); assertEquals(300_000_000L, jeonse.depositWon)
        assertEquals(sale.id, listingService.search(null, null, null, null, null, 0, 20, DealType.SALE, 900_000_000, 1_000_000_000).content.single().id)
    }

    @Test fun `거래 유형별 필수 금액 누락과 모순은 무료나 다른 거래로 보정하지 않는다`() {
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.create(unitId, values().copy(depositWon = null)) }
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.create(unitId, values().copy(dealType = DealType.SALE, salePriceWon = 800_000_000)) }
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.create(unitId, values().copy(dealType = DealType.JEONSE, monthlyRentWon = null, depositWon = 0)) }
        val draft = listingService.create(unitId, values())
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.update(draft.id, update(draft, values().copy(dealType = DealType.JEONSE, monthlyRentWon = null))) }
    }

    @Test fun `각 내부 공간의 완료 모델을 독립적으로 선택하고 중복 공간이나 잘못된 조합을 거부한다`() {
        fun selected(name: String): ModelSelectionRequest {
            val space = unitService.createSpace(unitId, SpaceCreateRequest(name))
            val room = rooms.findById(space.id).orElseThrow()
            val model = models.saveAndFlush(Room3DModel(room = room, status = ModelStatus.SUCCESS, modelUrl = "https://archive.example/$name.glb"))
            val asset = assets.saveAndFlush(MediaAsset(unitId = unitId, ownerUserId = ownerId, kind = MediaKind.MODEL, status = MediaStatus.VERIFIED,
                objectKey = "fixed-$name.glb", sizeBytes = 100, contentType = "model/gltf-binary", sourceModelId = model.id))
            return ModelSelectionRequest(space.id, requireNotNull(asset.id), requireNotNull(model.id))
        }
        val living = selected("거실"); val bedroom = selected("침실")
        val input = values().copy(modelSelections = listOf(living, bedroom))
        val draft = listingService.create(unitId, input)
        val published = listingService.publish(draft.id, ListingVersionRequest(draft.version))
        assertEquals(listOf("거실", "침실"), listingService.publicDetail(published.id).modelSelections.map { it.spaceName })
        assertEquals(living.selectedModelId, published.selectedModelId)
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.update(draft.id, update(published, input.copy(modelSelections = listOf(living, living)))) }
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.update(draft.id, update(published, input.copy(modelSelections = listOf(living.copy(modelAssetId = bedroom.modelAssetId))))) }
        val reordered = listingService.update(draft.id, update(published, input.copy(modelSelections = listOf(bedroom, living))))
        assertEquals(listOf(bedroom.spaceId, living.spaceId), reordered.modelSelections.map { it.spaceId })
        assertEquals(bedroom.selectedModelId, reordered.selectedModelId)
        val cleared = listingService.update(draft.id, update(reordered, input.copy(modelSelections = emptyList())))
        assertTrue(cleared.version > reordered.version); assertTrue(cleared.modelSelections.isEmpty()); assertNull(cleared.selectedModelId)
    }

    @Test fun `즐겨찾기는 사용자별 멱등하고 숨김 매물의 내용과 식별자를 노출하지 않는다`() {
        val draft = listingService.create(unitId, values())
        asUser(visitorId)
        assertStatus(HttpStatus.NOT_FOUND) { favoriteService.save(draft.id) }
        asUser(ownerId)
        val published = listingService.publish(draft.id, ListingVersionRequest(draft.version))
        asUser(visitorId)
        assertTrue(favoriteService.save(draft.id).favorited); assertTrue(favoriteService.save(draft.id).favorited)
        assertEquals(1L, favoriteService.mine(0, 20).totalElements)
        assertEquals(listOf(draft.id), favoriteService.ids()); assertNull(favoriteService.mine(0, 20).content.single().unitName)
        asUser(strangerId)
        assertTrue(favoriteService.ids().isEmpty()); assertFalse(favoriteService.remove(draft.id).favorited)
        asUser(ownerId)
        listingService.hide(draft.id, ListingVersionRequest(published.version))
        asUser(visitorId)
        assertTrue(favoriteService.ids().isEmpty()); assertTrue(favoriteService.mine(0, 20).content.isEmpty())
        assertFalse(favoriteService.remove(draft.id).favorited); assertFalse(favoriteService.remove(draft.id).favorited)
    }

    private fun verifiedLocation(expiresAt: LocalDateTime = LocalDateTime.now().plusMinutes(15)) = locations.saveAndFlush(VerifiedLocation(
        ownerUserId = ownerId, canonicalAddress = "서울 성동구 왕십리로 83", roadAddress = "서울 성동구 왕십리로 83",
        jibunAddress = "서울 성동구 성수동1가 656-78", postalCode = "04769", regionCode = "1120011400", regionName = "서울 성동구 성수동1가",
        latitude = BigDecimal("37.5445000"), longitude = BigDecimal("127.0443000"), expiresAt = expiresAt))
    private fun map(limit: Int = 200, q: String? = null) = mapService.search(37.5, 127.0, 37.6, 127.1, limit, null, null, null, null, null, null, null, null, q)

    @Test fun `structured unit details persist with tri state unknown and public privacy remains intact`() {
        val original = unitService.get(unitId)
        assertNull(original.details.hasParking); assertNull(original.details.roomCount)
        val details = UnitDetails(BuildingType.VILLA,8,2,1,FacingDirection.SOUTH,
            hasElevator=false,hasParking=true,petsAllowed=false,furnished=null)
        val updated = unitService.update(unitId,UnitUpdateRequest(original.version,original.name,original.regionCode,
            original.publicAddress,floor=3,areaSqm=BigDecimal("25.50"),availableFrom=LocalDate.of(2026,11,1),details=details))
        val draft = listingService.create(unitId,values())
        val published = listingService.publish(draft.id,ListingVersionRequest(draft.version))
        entityManager.clear()
        assertEquals(details,unitService.get(unitId).details)
        val visible = listingService.publicDetail(published.id)
        assertEquals(details,visible.details); assertEquals(3,visible.floor)
        assertNull(visible.unitName); assertNull(visible.canonicalAddress); assertNull(visible.latitude)
        assertEquals(BigDecimal("25.50"),visible.areaSqm); assertTrue(updated.version > original.version)
    }

    @Test fun `invalid cross field details cannot change unit or attached listing versions`() {
        val unit = unitService.get(unitId)
        val draft = listingService.create(unitId,values())
        assertStatus(HttpStatus.BAD_REQUEST) { unitService.update(unitId,UnitUpdateRequest(unit.version,unit.name,unit.regionCode,
            floor=5,details=UnitDetails(totalFloors=4))) }
        assertStatus(HttpStatus.BAD_REQUEST) { unitService.create(UnitCreateRequest(unit.propertyId,"invalid","SEOUL",details=UnitDetails(roomCount=100))) }
        assertEquals(unit.version,unitService.get(unitId).version)
        assertEquals(draft.version,listingService.draft(draft.id).version)
    }

    @Test fun `detail filters exclude unknown booleans and are shared with bounded map search`() {
        val location=verifiedLocation()
        val property=propertyService.create(PropertyCreateRequest("조건 건물","비공개",locationToken=location.id))
        fun add(details: UnitDetails, area: String?, moveIn: LocalDate?): ListingView {
            val unit=unitService.create(UnitCreateRequest(property.id,"private","IGNORED",floor=2,
                areaSqm=area?.let(::BigDecimal),availableFrom=moveIn,locationVisibility=LocationVisibility.BUILDING,details=details))
            val photo=image(unit.id,ownerId)
            val draft=listingService.create(unit.id,values().copy(representativeAssetId=photo,photoAssetIds=listOf(photo)))
            return listingService.publish(draft.id,ListingVersionRequest(draft.version))
        }
        val yes=add(UnitDetails(BuildingType.OFFICETEL,roomCount=2,direction=FacingDirection.SOUTH,
            hasElevator=true,hasParking=true,petsAllowed=false,furnished=true),"32.50",LocalDate.of(2026,11,1))
        val no=add(UnitDetails(hasParking=false),"10.00",LocalDate.of(2027,1,1))
        add(UnitDetails(),null,null)
        val filter=ListingDetailsFilter(BuildingType.OFFICETEL,FacingDirection.SOUTH,BigDecimal("30"),BigDecimal("40"),2,
            hasElevator=true,hasParking=true,petsAllowed=false,furnished=true,availableBy=LocalDate.of(2026,12,1))
        val list=listingService.search(null,null,null,null,null,0,20,details=filter)
        val map=mapService.search(37.5,127.0,37.6,127.1,200,null,null,null,null,null,null,null,null,null,details=filter)
        assertEquals(listOf(yes.id),list.content.map { it.id }); assertEquals(listOf(yes.id),map.content.map { it.id })
        val knownNo=listingService.search(null,null,null,null,null,0,20,details=ListingDetailsFilter(hasParking=false))
        assertEquals(listOf(no.id),knownNo.content.map { it.id })
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.search(null,null,null,null,null,0,20,
            details=ListingDetailsFilter(minAreaSqm=BigDecimal("50"),maxAreaSqm=BigDecimal("20"))) }
    }

    @Test fun `price sorting is type specific deterministic and area sorting puts unknown last`() {
        fun add(area: String?, rent: Long, deposit: Long, sale: Long): Pair<ListingView,ListingView> {
            val property=propertyService.create(PropertyCreateRequest("정렬 건물","비공개"))
            val unit=unitService.create(UnitCreateRequest(property.id,"private","SEOUL",areaSqm=area?.let(::BigDecimal)))
            val photo=image(unit.id,ownerId)
            val monthly=listingService.create(unit.id,values().copy(depositWon=deposit,monthlyRentWon=rent,representativeAssetId=photo,photoAssetIds=listOf(photo)))
            val saleListing=listingService.create(unit.id,ListingCreateRequest("매매",dealType=DealType.SALE,salePriceWon=sale,
                representativeAssetId=photo,photoAssetIds=listOf(photo)))
            return listingService.publish(monthly.id,ListingVersionRequest(monthly.version)) to
                listingService.publish(saleListing.id,ListingVersionRequest(saleListing.version))
        }
        val a=add("10",900000,1000000,800000000)
        val b=add("30",500000,90000000,400000000)
        val c=add(null,500000,50000000,600000000)
        fun ordered(type: DealType, sort: ListingSort,page:Int=0,size:Int=20)=listingService.search(null,null,null,null,null,page,size,dealType=type,sort=sort)
        assertEquals(listOf(c.first.id,b.first.id,a.first.id),ordered(DealType.MONTHLY_RENT,ListingSort.PRICE_ASC).content.map { it.id })
        assertEquals(listOf(a.second.id,c.second.id,b.second.id),ordered(DealType.SALE,ListingSort.PRICE_DESC).content.map { it.id })
        assertEquals(listOf(a.first.id,b.first.id,c.first.id),ordered(DealType.MONTHLY_RENT,ListingSort.AREA_ASC).content.map { it.id })
        assertEquals(listOf(b.first.id,a.first.id,c.first.id),ordered(DealType.MONTHLY_RENT,ListingSort.AREA_DESC).content.map { it.id })
        assertEquals(3L,ordered(DealType.MONTHLY_RENT,ListingSort.PRICE_ASC,1,1).totalElements)
        assertEquals(b.first.id,ordered(DealType.MONTHLY_RENT,ListingSort.PRICE_ASC,1,1).content.single().id)
        assertStatus(HttpStatus.BAD_REQUEST) { listingService.search(null,null,null,null,null,0,20,sort=ListingSort.PRICE_ASC) }
    }

    @Test fun `completion withdraws public listing favorites and new inquiries but preserves participant snapshots`() {
        val draft=listingService.create(unitId,values())
        val published=listingService.publish(draft.id,ListingVersionRequest(draft.version))
        asUser(visitorId)
        favoriteService.save(published.id)
        val inquiry=inquiryService.create(published.id,InquiryCreateRequest(published.version,"문의 당시 조건"))
        asUser(ownerId)
        val completed=listingService.complete(published.id,ListingVersionRequest(published.version))
        assertEquals(ListingStatus.COMPLETED,completed.status); assertNotNull(completed.completedAt)
        assertTrue(completed.version>published.version); assertEquals(completed.id,listingService.mine(0,20).content.single().id)
        assertTrue(search().content.isEmpty()); assertStatus(HttpStatus.NOT_FOUND) { listingService.publicDetail(completed.id) }
        assertStatus(HttpStatus.CONFLICT) { listingService.publish(completed.id,ListingVersionRequest(completed.version)) }
        assertStatus(HttpStatus.CONFLICT) { listingService.hide(completed.id,ListingVersionRequest(completed.version)) }
        assertStatus(HttpStatus.CONFLICT) { listingService.update(completed.id,update(completed,values())) }
        val reply=inquiryService.reply(inquiry.id,InquiryReplyRequest("모집은 종료되었습니다"))
        assertEquals(published.version,reply.listingSnapshot.version); assertEquals(500000L,reply.listingSnapshot.monthlyRentWon)
        asUser(visitorId)
        assertTrue(favoriteService.ids().isEmpty()); assertTrue(favoriteService.mine(0,20).content.isEmpty())
        assertStatus(HttpStatus.NOT_FOUND) { inquiryService.create(completed.id,InquiryCreateRequest(completed.version,"종료 후 문의")) }
        assertStatus(HttpStatus.FORBIDDEN) { listingService.reopen(completed.id,ListingVersionRequest(completed.version)) }
        asUser(ownerId)
        assertStatus(HttpStatus.CONFLICT) { listingService.reopen(completed.id,ListingVersionRequest(published.version)) }
        val reopened=listingService.reopen(completed.id,ListingVersionRequest(completed.version))
        assertEquals(ListingStatus.DRAFT,reopened.status); assertNull(reopened.completedAt); assertNull(reopened.publishedAt)
        assertTrue(search().content.isEmpty())
        assertEquals(ListingStatus.PUBLISHED,listingService.publish(reopened.id,ListingVersionRequest(reopened.version)).status)
    }

    @Test fun `completion validates lifecycle and version and withdraws mapped listings`() {
        val location=verifiedLocation();val property=propertyService.create(PropertyCreateRequest("지도","비공개",locationToken=location.id))
        val unit=unitService.create(UnitCreateRequest(property.id,"private","IGNORED",locationVisibility=LocationVisibility.BUILDING))
        val photo=image(unit.id,ownerId)
        val draft=listingService.create(unit.id,values().copy(representativeAssetId=photo,photoAssetIds=listOf(photo)))
        assertStatus(HttpStatus.CONFLICT) { listingService.complete(draft.id,ListingVersionRequest(draft.version)) }
        val published=listingService.publish(draft.id,ListingVersionRequest(draft.version))
        assertEquals(published.id,map().content.single().id)
        assertStatus(HttpStatus.CONFLICT) { listingService.complete(published.id,ListingVersionRequest(draft.version)) }
        val hidden=listingService.hide(published.id,ListingVersionRequest(published.version))
        val completed=listingService.complete(hidden.id,ListingVersionRequest(hidden.version))
        assertEquals(ListingStatus.COMPLETED,completed.status);assertTrue(map().content.isEmpty())
    }

    private fun values(title: String = "301호 월세", deposit: Long? = 10_000_000L, photos: Boolean = true) =
        ListingCreateRequest(title = title, depositWon = deposit, monthlyRentWon = 500_000,
            representativeAssetId = assetId.takeIf { photos }, photoAssetIds = if (photos) listOf(assetId) else emptyList())
    private fun update(current: ListingView, v: ListingCreateRequest) = ListingUpdateRequest(current.version, v.title, v.description,
        v.depositWon, v.monthlyRentWon, v.managementFeeWon, v.managementFeeIncludes, v.representativeAssetId, v.photoAssetIds,
        v.modelAssetId, v.selectedModelId, v.spaceId, v.dealType, v.salePriceWon, v.modelSelections)
    private fun search() = listingService.search(null, null, null, null, null, 0, 20)
    private fun image(unit: Long, owner: Long) = requireNotNull(assets.saveAndFlush(MediaAsset(unitId = unit,
        ownerUserId = owner, kind = MediaKind.IMAGE, status = MediaStatus.VERIFIED, objectKey = "fixed-$unit-$owner.jpg",
        sizeBytes = 32, contentType = "image/jpeg")).id)
    private fun user(name: String) = requireNotNull(users.saveAndFlush(User(email = "$name@example.com", name = name,
        provider = "TEST", providerId = name, role = "GUEST")).id)
    private fun asUser(id: Long) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(AuthenticatedUser(id), null, emptyList())
    }
    private fun assertStatus(status: HttpStatus, action: () -> kotlin.Unit) {
        assertEquals(status, assertThrows(ApiException::class.java, action).status)
    }
}
