package com.hanyang.backend.domain.model3d

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/rooms/{roomId}/models")
class Room3DModelController(
    private val room3DModelService: Room3DModelService
) {

    /** 여러 S3 이미지 URL을 받아 1차 분석을 시작합니다. */
    @PostMapping("/analyze")
    fun analyzeRoom(
        @PathVariable roomId: Long,
        @RequestBody @Valid request: AnalyzeModelRequest
    ): ResponseEntity<ModelStatusResponse> {
        val response = room3DModelService.analyzeRoom(roomId, request)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }

    /**
     * 현재 상태 조회
     *
     * 프론트 -> 백엔드
     * GET /api/rooms/{roomId}/models/status
     */
    @GetMapping("/status")
    fun getModelStatus(
        @PathVariable roomId: Long
    ): ResponseEntity<ModelStatusResponse> {
        val response = room3DModelService.getModelStatus(roomId)
        return ResponseEntity.ok(response)
    }

    /** 보정 좌표를 전송한 뒤 AI 재구성 요청을 순차 실행합니다. */
    @PostMapping("/reconstruct")
    fun reconstruct(
        @PathVariable roomId: Long,
        @RequestBody @Valid request: CorrectionRequestDto
    ): ResponseEntity<ModelStatusResponse> {
        val response = room3DModelService.applyCorrectionAndReconstruct(roomId, request)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }

    /**
     * AI 서버 상태를 다시 가져와서 백엔드 DB에 반영
     *
     * 프론트 -> 백엔드
     * POST /api/rooms/{roomId}/models/refresh
     */
    @PostMapping("/refresh")
    fun refreshFromAi(
        @PathVariable roomId: Long
    ): ResponseEntity<ModelStatusResponse> {
        val response = room3DModelService.refreshFromAi(roomId)
        return ResponseEntity.ok(response)
    }
}
