package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.media.MediaService
import com.hanyang.backend.domain.media.MediaConcurrencyGuard
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/internal/ai")
class InternalAiController(private val pipeline: Room3DModelService, private val media: MediaService, private val guard: MediaConcurrencyGuard) {
    @GetMapping("/assets/{assetId}/content")
    fun image(@PathVariable assetId: Long, response: HttpServletResponse) {
        guard.run {
            val read = media.internalImageRead(assetId)
            val bytes = requireNotNull(read.bytes)
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Content-Disposition", "inline")
            response.contentType = read.contentType
            response.setContentLength(bytes.size)
            response.outputStream.write(bytes)
            response.outputStream.flush()
        }
    }
    @PostMapping("/webhook")
    fun webhook(@Valid @RequestBody request: AiWebhookRequest): ResponseEntity<Void> {
        require(request.roomDataJson == null || request.roomDataJson.toByteArray(Charsets.UTF_8).size <= AiPayloadLimits.MAX_RESULT_BYTES) { "AI 결과 JSON은 16MiB를 초과할 수 없습니다." }
        pipeline.handleAiWebhook(request)
        return ResponseEntity.noContent().build()
    }
}
