package com.hanyang.backend.domain.model3d

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/*
 * AiServerClient
 *
 * 역할:
 * - Spring Boot 백엔드가 AI 서버(FastAPI)를 호출하는 전용 클래스입니다.
 * - 프론트는 AI 서버를 직접 호출하지 않습니다.
 * - 프론트 → 백엔드 → AI 서버 순서로 갑니다.
 *
 * AI 서버 주소는 application.yaml의 ai.server.base-url 설정에서 주입받습니다.
 */
@Component
class AiServerClient(
    /*
     * [설정값 주입]
     *
     * application.yaml의 ai.server.base-url 값을 읽어옵니다.
     *
     * @Value("\${ai.server.base-url}") 로 써도 동작은 하지만,
     * Kotlin에서 경고가 떠서 @param:Value 형태로 작성했습니다.
     */
    @param:Value("\${ai.server.base-url}")
    private val aiServerBaseUrl: String
) {
    /*
     * [WebClient 생성]
     *
     * WebClient는 외부 서버로 HTTP 요청을 보낼 때 사용합니다.
     * 여기서는 AI 서버를 호출하기 위해 사용합니다.
     *
     * 요청 기준 주소는 환경별 설정값인 aiServerBaseUrl을 사용합니다.
     */
    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(aiServerBaseUrl)
            .build()
    }

    /*
     * [Phase 1] 이미지 분석 요청
     *
     * 백엔드가 AI 서버에 보내는 요청:
     * POST /api/v1/rooms/analyze
     *
     * 프론트는 백엔드에 imageUrls로 보냅니다.
     * 백엔드는 Service에서 imageUrls를 imagePaths로 바꿔서
     * 이 함수에 AiAnalyzeRequestDto 형태로 넘깁니다.
     *
     * 예시 request:
     * {
     *   imagePaths: ["https://s3.../image.jpg"],
     *   roomId: "백엔드가 만든 UUID",
     *   roomName: "거실",
     *   userId: "UUID 형식 문자열"
     * }
     */
    // Mono<Void>: AI 서버에 보내는 실행 계획
    fun analyze(request: AiAnalyzeRequestDto): Mono<Void> {
        return webClient.post()
            .uri("/api/v1/rooms/analyze")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(Void::class.java)
    }


    /*
     * [Correction] 사용자 보정 데이터 전달 (비동기로 변경)
     */
    fun sendCorrection(aiRoomId: String, request: CorrectionRequestDto): Mono<Void> {
        return webClient.patch()
            .uri("/api/v1/rooms/{$aiRoomId}/correction", aiRoomId)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(Void::class.java)

    }

    /*
     * [Phase 2] 최종 3D 재구성 요청 (비동기로 변경)
     */
    fun reconstruct(aiRoomId: String): Mono<Void> {
        return webClient.post()
            .uri("/api/v1/rooms/$aiRoomId/reconstruct")
            .retrieve()
            .bodyToMono(Void::class.java)
    }

    /*
     * [조회] AI 서버에 저장된 Room 상태 조회
     *
     * 백엔드가 AI 서버에 보내는 요청:
     * GET /api/v1/rooms/{room_id}
     *
     * 백엔드 DB와 AI 서버 상태를 다시 맞추고 싶을 때 사용합니다.
     */
    fun getRoom(aiRoomId: String): AiRoomResponseDto {
        return webClient.get()
            .uri("/api/v1/rooms/$aiRoomId")
            .retrieve()
            .bodyToMono(AiRoomResponseDto::class.java)
            .block()
            ?: throw IllegalStateException("AI 서버 room 조회 응답이 비어 있습니다.")
    }
}
