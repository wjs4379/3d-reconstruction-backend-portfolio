package com.hanyang.backend.domain.property

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/properties")
class PropertyController(
    private val propertyService: PropertyService
) {
    // 1. 프론트에서 건물 등록하기
    @PostMapping
    fun createProperty(@RequestBody request: Map<String, String>): ResponseEntity<Property> {
        val title = request["title"] ?: ""
        val address = request["address"] ?: ""
        val description = request["description"]

        val property = propertyService.createProperty(title, description, address)
        return ResponseEntity.ok(property)
    }

    // 2. 프론트에서 건물 목록 불러오기 (이 리스트에서 1번 건물을 클릭하면 거기로 들어가서 방을 만듦)
    @GetMapping
    fun getAllProperties(): ResponseEntity<List<Property>> {
        val properties = propertyService.getAllProperties()
        return ResponseEntity.ok(properties)
    }
}