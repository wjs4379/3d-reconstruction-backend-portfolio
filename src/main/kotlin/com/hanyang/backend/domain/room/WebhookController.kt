package com.hanyang.backend.domain.room

import com.hanyang.backend.domain.model3d.Room3DModelService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/webhook")
class WebhookController(
    private val room3DModelService: Room3DModelService
) {

    /** AI 서버가 분석 및 재구성 결과를 전달하는 콜백입니다. */
    @PostMapping
    fun handleAiWebhook(
        @RequestBody request: AiWebhookRequest
    ): ResponseEntity<String> {
        room3DModelService.handleAiWebhook(request)
        return ResponseEntity.ok("Webhook received successfully")
    }
}
