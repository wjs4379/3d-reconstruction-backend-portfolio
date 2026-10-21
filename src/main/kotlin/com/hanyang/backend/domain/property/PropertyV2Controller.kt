package com.hanyang.backend.domain.property

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.security.CurrentUser
import com.hanyang.backend.domain.location.LocationService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

data class PropertyCreateRequest(
    @field:NotBlank @field:Size(max = 255) val title: String,
    @field:NotBlank @field:Size(max = 255) val address: String,
    @field:Size(max = 5000) val description: String? = null,
    @field:Size(max = 36) val locationToken: String? = null
)
data class PropertyView(val id: Long, val title: String, val address: String, val description: String?,
    val locationToken: String?, val canonicalAddress: String?, val postalCode: String?, val regionCode: String?) {
    companion object { fun from(property: Property) = PropertyView(requireNotNull(property.id), property.title, property.address, property.description,
        property.location?.id, property.location?.canonicalAddress, property.location?.postalCode, property.location?.regionCode) }
}
@Service
class PropertyV2Service(private val properties: PropertyRepository, private val currentUser: CurrentUser, private val locations: LocationService) {
    @Transactional fun create(request: PropertyCreateRequest) = PropertyView.from(properties.save(Property(title = request.title.trim(), address = request.address.trim(), description = request.description?.trim(), creatorUserId = currentUser.id(), location = request.locationToken?.let(locations::claim))))
    @Transactional(readOnly = true) fun mine(): List<PropertyView> = properties.findByCreatorUserId(currentUser.id()).map(PropertyView::from)
    @Transactional(readOnly = true) fun get(id: Long): PropertyView {
        val property = properties.findById(id).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "PROPERTY_NOT_FOUND", "건물을 찾을 수 없습니다.") }
        if (property.creatorUserId != currentUser.id()) throw ApiException(HttpStatus.FORBIDDEN, "PROPERTY_FORBIDDEN", "건물 관리 권한이 없습니다.")
        return PropertyView.from(property)
    }
}
@RestController
@RequestMapping("/api/v2/properties")
class PropertyV2Controller(private val service: PropertyV2Service) {
    @PostMapping fun create(@Valid @RequestBody request: PropertyCreateRequest) = service.create(request)
    @GetMapping fun mine() = service.mine()
    @GetMapping("/{propertyId}") fun get(@PathVariable propertyId: Long) = service.get(propertyId)
}
