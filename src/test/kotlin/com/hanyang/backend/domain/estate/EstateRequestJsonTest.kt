package com.hanyang.backend.domain.estate

import com.hanyang.backend.domain.inquiry.InquiryCreateRequest
import com.hanyang.backend.domain.listing.ListingCreateRequest
import com.hanyang.backend.domain.listing.ListingVersionRequest
import com.hanyang.backend.domain.unit.UnitCreateRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import com.hanyang.backend.domain.listing.ModelSelectionRequest
import com.hanyang.backend.domain.listing.DealType
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class EstateRequestJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test fun `누락과 null 버전은 거부하고 명시적인 초기 버전 0은 허용한다`() {
        assertThrows(Exception::class.java) { mapper.readValue("{}", ListingVersionRequest::class.java) }
        assertThrows(Exception::class.java) { mapper.readValue("""{"expectedVersion":null}""", ListingVersionRequest::class.java) }
        assertEquals(0L, mapper.readValue("""{"expectedVersion":0}""", ListingVersionRequest::class.java).expectedVersion)
    }
    @Test fun `보증금 누락과 null을 무료 보증금으로 바꾸지 않는다`() {
        assertNull(mapper.readValue("""{"title":"월세","monthlyRentWon":500000}""", ListingCreateRequest::class.java).depositWon)
        assertNull(mapper.readValue("""{"title":"월세","depositWon":null,"monthlyRentWon":500000}""", ListingCreateRequest::class.java).depositWon)
        val valid = mapper.readValue("""{"title":"월세","depositWon":0,"monthlyRentWon":500000}""", ListingCreateRequest::class.java)
        assertEquals(0L, valid.depositWon)
        assertEquals(emptyList<Long>(), valid.photoAssetIds)
    }
    @Test fun `문의 버전 누락은 거부하고 이전 message 입력도 content로 읽는다`() {
        assertThrows(Exception::class.java) { mapper.readValue("""{"content":"입주 문의"}""", InquiryCreateRequest::class.java) }
        val request = mapper.readValue("""{"expectedVersion":1,"message":"입주 문의"}""", InquiryCreateRequest::class.java)
        assertEquals("입주 문의", request.content)
    }
    @Test fun `매매 조건은 다른 거래 금액을 요구하지 않고 공간 모델 식별자는 누락이나 null을 거부한다`() {
        val sale = mapper.readValue("""{"title":"매매","dealType":"SALE","salePriceWon":900000000}""", ListingCreateRequest::class.java)
        assertEquals(DealType.SALE, sale.dealType); assertNull(sale.depositWon); assertNull(sale.monthlyRentWon)
        assertThrows(Exception::class.java) { mapper.readValue("""{"spaceId":1,"modelAssetId":2}""", ModelSelectionRequest::class.java) }
        assertThrows(Exception::class.java) { mapper.readValue("""{"spaceId":1,"modelAssetId":2,"selectedModelId":null}""", ModelSelectionRequest::class.java) }
    }
    @Test fun `건물 식별자 누락과 null을 0으로 바꾸지 않는다`() {
        assertThrows(Exception::class.java) { mapper.readValue("""{"name":"301호","regionCode":"SEOUL"}""", UnitCreateRequest::class.java) }
        assertThrows(Exception::class.java) { mapper.readValue("""{"propertyId":null,"name":"301호","regionCode":"SEOUL"}""", UnitCreateRequest::class.java) }
    }
}
