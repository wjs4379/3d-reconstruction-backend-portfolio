package com.hanyang.backend.domain.model3d

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/** FastAPI AI 서버의 분석·보정·재구성 API를 호출합니다. */
@Component
class AiServerClient(
    @param:Value("\${ai.server.base-url}")
    private val aiServerBaseUrl: String
) {
    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(aiServerBaseUrl)
            .build()
    }

    /** 1차 이미지 분석을 요청합니다. */
    fun analyze(request: AiAnalyzeRequestDto): Mono<Void> {
        return webClient.post()
            .uri("/api/v1/rooms/analyze")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(Void::class.java)
    }

    /** 사용자가 보정한 좌표를 AI 서버에 전달합니다. */
    fun sendCorrection(
        aiRoomId: String,
        request: CorrectionRequestDto
    ): Mono<Void> {
        return webClient.patch()
            .uri("/api/v1/rooms/{aiRoomId}/correction", aiRoomId)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(Void::class.java)
    }

    /** 보정 좌표를 기반으로 최종 재구성을 요청합니다. */
    fun reconstruct(aiRoomId: String): Mono<Void> {
        return webClient.post()
            .uri("/api/v1/rooms/{aiRoomId}/reconstruct", aiRoomId)
            .retrieve()
            .bodyToMono(Void::class.java)
    }

    /** Webhook 유실 시 AI 서버의 현재 상태와 결과를 다시 조회합니다. */
    fun getRoom(aiRoomId: String): AiRoomResponseDto {
        return webClient.get()
            .uri("/api/v1/rooms/{aiRoomId}", aiRoomId)
            .retrieve()
            .bodyToMono(AiRoomResponseDto::class.java)
            .block()
            ?: throw IllegalStateException("AI 서버 room 조회 응답이 비어 있습니다.")
    }
}
