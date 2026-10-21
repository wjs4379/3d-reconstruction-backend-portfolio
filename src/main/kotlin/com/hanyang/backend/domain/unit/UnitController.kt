package com.hanyang.backend.domain.unit

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class UnitController(private val service: UnitService) {
    @PostMapping("/units") fun create(@Valid @RequestBody request: UnitCreateRequest) = service.create(request)
    @PostMapping("/properties/{propertyId}/units")
    fun createAtProperty(@PathVariable propertyId: Long, @Valid @RequestBody request: PropertyUnitCreateRequest) = service.create(request.withProperty(propertyId))
    @GetMapping("/me/units") fun mine(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = service.mine(page, size)
    @PatchMapping("/units/{unitId}") fun update(@PathVariable unitId: Long, @Valid @RequestBody request: UnitUpdateRequest) = service.update(unitId, request)
    @GetMapping("/units/{unitId}") fun get(@PathVariable unitId: Long) = service.get(unitId)
    @PostMapping("/units/{unitId}/spaces") fun space(@PathVariable unitId: Long, @Valid @RequestBody request: SpaceCreateRequest) = service.createSpace(unitId, request)
    @GetMapping("/units/{unitId}/spaces") fun spaces(@PathVariable unitId: Long) = service.spaces(unitId)
    @PostMapping("/units/{unitId}/spaces/{spaceId}/link") fun link(@PathVariable unitId: Long, @PathVariable spaceId: Long) = service.linkExistingSpace(unitId, spaceId)
}
