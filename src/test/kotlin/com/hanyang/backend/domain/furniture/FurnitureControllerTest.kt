package com.hanyang.backend.domain.furniture

import com.hanyang.backend.common.ApiExceptionHandler
import com.hanyang.backend.domain.media.MediaConcurrencyGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class FurnitureControllerTest {
    private val catalog = mock(FurnitureCatalog::class.java)
    private lateinit var mvc: MockMvc
    private val bytes = "catalog-model-byte-content".toByteArray()
    private val digest = "a".repeat(64)

    @BeforeEach fun setUp() {
        `when`(catalog.read("microwave")).thenReturn(FurnitureContent(bytes, digest))
        mvc = MockMvcBuilders.standaloneSetup(FurnitureController(catalog, MediaConcurrencyGuard()))
            .setControllerAdvice(ApiExceptionHandler()).build()
    }

    @Test fun `GET sends actual model bytes length GLB mime and strong cache validator`() {
        mvc.perform(get("/api/v2/furniture/microwave/content"))
            .andExpect(status().isOk).andExpect(content().bytes(bytes))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "model/gltf-binary"))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.size.toLong()))
            .andExpect(header().string(HttpHeaders.ETAG, "\"$digest\""))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=3600"))
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
    }

    @Test fun `HEAD describes a GET without a body and ignores Range`() {
        mvc.perform(head("/api/v2/furniture/microwave/content").header(HttpHeaders.RANGE, "bytes=0-3"))
            .andExpect(status().isOk).andExpect(content().bytes(byteArrayOf()))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.size.toLong()))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
    }

    @Test fun `matching normal weak and list validators return 304 with no model body`() {
        for (condition in listOf("\"$digest\"", "W/\"$digest\"", "\"other\", W/\"$digest\"", "*")) {
            mvc.perform(get("/api/v2/furniture/microwave/content").header(HttpHeaders.IF_NONE_MATCH, condition))
                .andExpect(status().isNotModified).andExpect(content().bytes(byteArrayOf()))
        }
        mvc.perform(get("/api/v2/furniture/microwave/content").header(HttpHeaders.IF_NONE_MATCH, "\"different\""))
            .andExpect(status().isOk).andExpect(content().bytes(bytes))
    }

    @Test fun `single explicit open ended suffix and clamped ranges return correct byte slices`() {
        for ((range, expected) in listOf("bytes=0-3" to 0..3, "bytes=5-" to 5..bytes.lastIndex,
            "bytes=-3" to (bytes.size-3)..bytes.lastIndex, "bytes=0-999999" to 0..bytes.lastIndex)) {
            mvc.perform(get("/api/v2/furniture/microwave/content").header(HttpHeaders.RANGE, range))
                .andExpect(status().isPartialContent)
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes ${expected.first}-${expected.last}/${bytes.size}"))
                .andExpect(content().bytes(bytes.copyOfRange(expected.first, expected.last + 1)))
        }
    }

    @Test fun `invalid overflowing multiple and unsatisfiable ranges never allocate or return full content`() {
        for (range in listOf("bytes=999-", "bytes=5-1", "bytes=-0", "bytes=-", "bytes=0-1,3-4", "items=0-1", "bytes=${"9".repeat(200)}-")) {
            mvc.perform(get("/api/v2/furniture/microwave/content").header(HttpHeaders.RANGE, range))
                .andExpect(status().isRequestedRangeNotSatisfiable)
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */${bytes.size}"))
                .andExpect(content().bytes(byteArrayOf()))
        }
    }

    @Test fun `If Range mismatch prevents a partial response with a different representation`() {
        mvc.perform(get("/api/v2/furniture/microwave/content").header(HttpHeaders.RANGE, "bytes=0-3")
            .header(HttpHeaders.IF_RANGE, "\"different\""))
            .andExpect(status().isOk).andExpect(content().bytes(bytes))
        mvc.perform(get("/api/v2/furniture/microwave/content").header(HttpHeaders.RANGE, "bytes=0-3")
            .header(HttpHeaders.IF_RANGE, "\"$digest\""))
            .andExpect(status().isPartialContent).andExpect(content().bytes(bytes.copyOfRange(0, 4)))
    }
}
