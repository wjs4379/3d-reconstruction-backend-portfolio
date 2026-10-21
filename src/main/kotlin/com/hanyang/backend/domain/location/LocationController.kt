package com.hanyang.backend.domain.location

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/locations")
class LocationController(private val service: LocationService) {
    @PostMapping("/resolve") fun resolve(@Valid @RequestBody request: LocationResolveRequest) = service.resolve(request)
}
