package com.hanyang.backend.domain.location

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.security.CurrentUser
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

data class LocationResolveRequest(@field:NotBlank @field:Size(max = 255) val address: String)
data class LocationView(
    val locationToken: String, val canonicalAddress: String, val roadAddress: String?, val jibunAddress: String?,
    val postalCode: String?, val regionCode: String, val regionName: String, val latitude: Double, val longitude: Double,
    val expiresAt: LocalDateTime
) {
    companion object {
        fun from(l: VerifiedLocation) = LocationView(l.id, l.canonicalAddress, l.roadAddress, l.jibunAddress, l.postalCode,
            l.regionCode, l.regionName, l.latitude.toDouble(), l.longitude.toDouble(), l.expiresAt)
    }
}
@Service
class LocationService(private val provider: LocationProvider, private val locations: LocationRepository, private val user: CurrentUser) {
    // External geocoding runs before the short persistence transaction owned by the repository.
    fun resolve(request: LocationResolveRequest): LocationView {
        if (request.address.isBlank() || request.address.length > 255) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADDRESS", "주소를 확인해 주세요.")
        val ownerId = user.id()
        val resolved = provider.resolve(request.address.trim())
        return LocationView.from(locations.save(VerifiedLocation(ownerUserId = ownerId, canonicalAddress = resolved.canonicalAddress,
            roadAddress = resolved.roadAddress, jibunAddress = resolved.jibunAddress, postalCode = resolved.postalCode,
            regionCode = resolved.regionCode, regionName = resolved.regionName, latitude = resolved.latitude, longitude = resolved.longitude)))
    }
    @Transactional(readOnly = true)
    fun claim(token: String): VerifiedLocation {
        if (runCatching { UUID.fromString(token) }.isFailure) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION_TOKEN", "주소 확인 결과를 다시 선택해 주세요.")
        val location = locations.findById(token).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "주소 확인 결과를 찾을 수 없습니다.") }
        if (location.ownerUserId != user.id()) throw ApiException(HttpStatus.FORBIDDEN, "LOCATION_FORBIDDEN", "본인이 확인한 주소만 선택할 수 있습니다.")
        if (!LocalDateTime.now().isBefore(location.expiresAt)) throw ApiException(HttpStatus.GONE, "LOCATION_TOKEN_EXPIRED", "주소 확인 결과가 만료되었습니다. 다시 확인해 주세요.")
        return location
    }
}
