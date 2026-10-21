package com.hanyang.backend.security

import com.hanyang.backend.domain.media.MediaConcurrencyGuard
import com.hanyang.backend.domain.media.MediaRead
import com.hanyang.backend.domain.media.MediaService
import com.hanyang.backend.domain.model3d.InternalAiController
import com.hanyang.backend.domain.model3d.Room3DModelService
import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(controllers=[InternalAiController::class])
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(SecurityConfig::class,JwtTokenProvider::class,SecurityErrorWriter::class,MediaConcurrencyGuard::class)
@TestPropertySource(properties=[
    "app.jwt.secret=test-only-jwt-secret-with-at-least-32-bytes",
    "app.ai.pipeline-enabled=true",
    "app.ai.service-key=test-only-ai-service-secret-at-least-32-bytes"
])
class InternalAiSecurityTest {
    @Autowired private lateinit var mvc:MockMvc
    @MockitoBean private lateinit var users:UserRepository
    @MockitoBean private lateinit var media:MediaService
    @MockitoBean private lateinit var pipeline:Room3DModelService
    private val key="test-only-ai-service-secret-at-least-32-bytes"

    @Test fun `internal callbacks and image fetch reject absent or wrong credentials`() {
        mvc.perform(get("/api/internal/ai/assets/1/content")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/internal/ai/assets/1/content").header("X-AI-Service-Key","wrong"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AI_SERVICE_UNAUTHORIZED"))
        mvc.perform(post("/api/internal/ai/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(media,pipeline)
    }
    @Test fun `verified internal image bytes stream without a redirect`() {
        `when`(media.internalImageRead(1)).thenReturn(MediaRead("image/png",bytes=byteArrayOf(1,2,3)))
        mvc.perform(get("/api/internal/ai/assets/1/content").header("X-AI-Service-Key",key))
            .andExpect(status().isOk).andExpect(content().bytes(byteArrayOf(1,2,3)))
            .andExpect(header().doesNotExist("Location")).andExpect(header().string("Cache-Control","no-store"))
    }
    @Test fun `valid service key accepts existing phase guarded webhook but does not authorize legacy routes`() {
        mvc.perform(post("/api/internal/ai/webhook").header("X-AI-Service-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("""{"aiRoomId":"task-1","status":"FAILED","roomDataJson":null}"""))
            .andExpect(status().isNoContent)
        verify(pipeline).handleAiWebhook(com.hanyang.backend.domain.room.AiWebhookRequest("task-1",com.hanyang.backend.domain.room.RenderStatus.FAILED,null))
        mvc.perform(post("/api/webhook").header("X-AI-Service-Key",key).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }
}
