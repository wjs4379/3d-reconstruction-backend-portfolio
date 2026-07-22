package com.hanyang.backend.domain.property

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PropertyService(
    private val propertyRepository: PropertyRepository
) {

    // 1. 진짜 매물 등록하는 로직
    @Transactional
    fun createProperty(title: String, description: String?, address: String): Property {
        val property = Property(
            title = title,
            description = description,
            address = address
        )
        return propertyRepository.save(property)
    }

    // 2. 진짜 전체 매물 목록 가져오는 로직
    @Transactional(readOnly = true)
    fun getAllProperties(): List<Property> {
        return propertyRepository.findAll()
    }
}