package com.hanyang.backend.domain.unit

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.common.PageView
import com.hanyang.backend.common.pageRequest
import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.location.LocationService
import com.hanyang.backend.domain.location.VerifiedLocation
import com.hanyang.backend.domain.listing.ListingRepository
import java.time.LocalDateTime
import com.hanyang.backend.domain.room.Room
import com.hanyang.backend.domain.room.RoomRepository
import com.hanyang.backend.domain.user.UserRepository
import com.hanyang.backend.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UnitService(
    private val units: UnitRepository, private val properties: PropertyRepository,
    private val rooms: RoomRepository, private val users: UserRepository, private val currentUser: CurrentUser,
    private val locations: LocationService, private val listings: ListingRepository
) {
    @Transactional
    fun create(request: UnitCreateRequest): UnitView {
        validateDetails(request.floor, request.details)
        val userId = currentUser.id()
        val property = properties.findById(request.propertyId).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "PROPERTY_NOT_FOUND", "건물을 찾을 수 없습니다.")
        }
        if (property.creatorUserId != userId) throw ApiException(HttpStatus.FORBIDDEN, "PROPERTY_FORBIDDEN", "본인이 등록한 건물에만 호실을 등록할 수 있습니다.")
        val location = chooseLocation(request.locationToken, property.location)
        validateVisibility(request.locationVisibility, location)
        return UnitView.from(units.saveAndFlush(Unit(property = property, creatorUserId = userId, name = request.name.trim(),
            regionCode = location?.regionCode ?: request.regionCode, publicAddress = publicAddress(request.locationVisibility, location, request.publicAddress),
            floor = request.floor, areaSqm = request.areaSqm, availableFrom = request.availableFrom,
            location = location, locationVisibility = request.locationVisibility, details = request.details)))
    }
    @Transactional
    fun update(unitId: Long, request: UnitUpdateRequest): UnitView {
        validateDetails(request.floor, request.details)
        val unit = units.findForUpdate(unitId) ?: throw ApiException(HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "호실을 찾을 수 없습니다.")
        if (unit.creatorUserId != currentUser.id()) throw ApiException(HttpStatus.FORBIDDEN, "UNIT_FORBIDDEN", "호실 관리 권한이 없습니다.")
        if (unit.version != request.expectedVersion) throw ApiException(HttpStatus.CONFLICT, "UNIT_VERSION_CONFLICT", "호실 정보가 변경되었습니다. 다시 조회해 주세요.")
        val location = chooseLocation(request.locationToken, unit.location ?: unit.property.location)
        if (unit.location != null && location?.canonicalAddress != unit.location?.canonicalAddress) throw ApiException(HttpStatus.CONFLICT,
            "UNIT_LOCATION_IMMUTABLE", "다른 건물은 새 호실로 등록해 주세요.")
        validateVisibility(request.locationVisibility, location)
        // Lock order: Unit, then every attached Listing by ascending ID. Publication commands only lock their Listing.
        val attached = listings.findAllForUnitUpdate(unitId)
        unit.name = request.name.trim(); unit.regionCode = location?.regionCode ?: request.regionCode
        unit.publicAddress = publicAddress(request.locationVisibility, location, request.publicAddress)
        unit.floor = request.floor; unit.areaSqm = request.areaSqm; unit.availableFrom = request.availableFrom
        unit.details = request.details
        unit.location = location; unit.locationVisibility = request.locationVisibility; unit.updatedAt = LocalDateTime.now()
        attached.forEach { it.updatedAt = LocalDateTime.now() }
        listings.flush()
        return UnitView.from(units.saveAndFlush(unit))
    }
    private fun validateDetails(floor: Int?, details: UnitDetails) {
        if ((floor != null && floor !in -20..200) ||
            (details.totalFloors != null && details.totalFloors !in 1..200) ||
            (details.roomCount != null && details.roomCount !in 0..99) ||
            (details.bathroomCount != null && details.bathroomCount !in 0..99) ||
            (floor != null && floor > 0 && details.totalFloors != null && floor > details.totalFloors)) {
            throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_UNIT_DETAILS","층수·전체 층수·방과 욕실 수를 확인해 주세요.")
        }
    }
    private fun chooseLocation(token: String?, saved: VerifiedLocation?): VerifiedLocation? {
        if (token == null || token == saved?.id) return saved
        val chosen = locations.claim(token)
        if (saved != null && chosen.canonicalAddress != saved.canonicalAddress) throw ApiException(HttpStatus.CONFLICT,
            "LOCATION_BUILDING_MISMATCH", "건물에 등록된 주소와 동일한 확인 결과를 선택해 주세요.")
        return chosen
    }
    private fun validateVisibility(visibility: LocationVisibility, location: VerifiedLocation?) {
        if (visibility == LocationVisibility.BUILDING && location == null) throw ApiException(HttpStatus.BAD_REQUEST,
            "VERIFIED_LOCATION_REQUIRED", "지도 공개에는 서버에서 확인한 건물 주소가 필요합니다.")
    }
    private fun publicAddress(visibility: LocationVisibility, location: VerifiedLocation?, manual: String?): String? =
        if (visibility == LocationVisibility.BUILDING) location?.canonicalAddress else manual?.trim()?.takeIf { it.isNotEmpty() }
    @Transactional(readOnly = true)
    fun mine(page: Int, size: Int): PageView<UnitView> = PageView.from(units.findByCreatorUserId(currentUser.id(), pageRequest(page, size)).map(UnitView::from))
    @Transactional(readOnly = true)
    fun get(id: Long): UnitView = UnitView.from(requireManagedUnit(id))
    @Transactional(readOnly = true)
    fun requireManagedUnit(unitId: Long): Unit {
        val unit = units.findById(unitId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "UNIT_NOT_FOUND", "호실을 찾을 수 없습니다.") }
        if (unit.creatorUserId != currentUser.id()) throw ApiException(HttpStatus.FORBIDDEN, "UNIT_FORBIDDEN", "호실 관리 권한이 없습니다.")
        return unit
    }
    @Transactional
    fun createSpace(unitId: Long, request: SpaceCreateRequest): SpaceView {
        val unit = requireManagedUnit(unitId)
        val owner = users.findById(currentUser.id()).orElseThrow { ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.") }
        val room = rooms.save(Room(property = unit.property, owner = owner, roomName = request.name.trim(), unit = unit))
        return SpaceView(requireNotNull(room.id), unitId, room.roomName)
    }
    @Transactional(readOnly = true)
    fun spaces(unitId: Long): List<SpaceView> {
        requireManagedUnit(unitId)
        return rooms.findByUnitId(unitId).map { SpaceView(requireNotNull(it.id), unitId, it.roomName) }
    }
    @Transactional
    fun linkExistingSpace(unitId: Long, spaceId: Long): SpaceView {
        val unit = requireManagedUnit(unitId)
        val room = rooms.findForUpdate(spaceId) ?: throw ApiException(HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND", "공간을 찾을 수 없습니다.")
        if (room.owner.id != currentUser.id() || room.property.id != unit.property.id) throw ApiException(HttpStatus.FORBIDDEN, "SPACE_FORBIDDEN", "동일 건물의 본인 공간만 연결할 수 있습니다.")
        if (room.unit != null && room.unit?.id != unitId) throw ApiException(HttpStatus.CONFLICT, "SPACE_ALREADY_LINKED", "다른 호실에 연결된 공간입니다.")
        room.unit = unit
        return SpaceView(spaceId, unitId, room.roomName)
    }
}
