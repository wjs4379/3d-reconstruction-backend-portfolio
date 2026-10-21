package com.hanyang.backend.domain.estate

import com.hanyang.backend.common.ApiExceptionHandler
import com.hanyang.backend.common.PageView
import com.hanyang.backend.domain.listing.*
import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.UserRepository
import com.hanyang.backend.security.SecurityConfig
import com.hanyang.backend.security.SecurityErrorWriter
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@WebMvcTest(controllers=[ListingController::class,ListingMapController::class])
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(SecurityConfig::class,JwtTokenProvider::class,SecurityErrorWriter::class,ApiExceptionHandler::class)
@TestPropertySource(properties=["app.jwt.secret=estate-final-test-secret-with-32-bytes-minimum","app.cors.allowed-origins=http://localhost:5173"])
class ListingSearchHttpContractTest {
    @Autowired private lateinit var mvc: MockMvc
    @MockitoBean private lateinit var listings: ListingService
    @MockitoBean private lateinit var maps: ListingMapService
    @MockitoBean private lateinit var users: UserRepository

    @Test fun `anonymous listing and map bind ISO date and explicit false without dropping filters`() {
        val filters=ListingDetailsFilter(hasParking=false,availableBy=LocalDate.of(2026,11,1))
        `when`(listings.search(null,null,null,null,null,0,20,null,null,null,LocationFilter.ALL,null,filters,ListingSort.AREA_ASC))
            .thenReturn(PageView<ListingView>(emptyList(),0,0,0,20))
        `when`(maps.search(37.5,127.0,37.6,127.1,200,null,null,null,null,null,null,null,null,null,filters))
            .thenReturn(ListingMapView(emptyList(),false,200))
        mvc.perform(get("/api/v2/listings").param("hasParking","false").param("availableBy","2026-11-01").param("sort","AREA_ASC"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content").isArray)
        mvc.perform(get("/api/v2/listings/map").param("south","37.5").param("west","127.0").param("north","37.6").param("east","127.1")
            .param("hasParking","false").param("availableBy","2026-11-01"))
            .andExpect(status().isOk).andExpect(jsonPath("$.limit").value(200))
        verify(listings).search(null,null,null,null,null,0,20,null,null,null,LocationFilter.ALL,null,filters,ListingSort.AREA_ASC)
        verify(maps).search(37.5,127.0,37.6,127.1,200,null,null,null,null,null,null,null,null,null,filters)
    }

    @Test fun `invalid calendar date sort and building type fail before querying storage`() {
        for(params in listOf(mapOf("availableBy" to "2026-02-30"),mapOf("sort" to "CHEAPEST"),mapOf("buildingType" to "UNKNOWN_TYPE"))) {
            val request=get("/api/v2/listings")
            params.forEach { (key,value)->request.param(key,value) }
            mvc.perform(request).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        verifyNoInteractions(listings,maps)
    }
}
