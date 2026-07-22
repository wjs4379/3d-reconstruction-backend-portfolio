package com.hanyang.backend.domain.user

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/auth")
class OAuthController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val oauthService: OAuthService

) {

    @PostMapping("/kakao")
    fun kakaoLogin(@RequestParam accessToken: String): UserResponseDto {
        val user = oauthService.loginWithKakao(accessToken)

        return UserResponseDto(
            userId = user.id,
            email = user.email,
            name = user.name,
            provider = user.provider,
            role = user.role
        )
    }

    @PostMapping("/google")
    fun googleLogin(@RequestParam accessToken: String): UserResponseDto {
        val user = oauthService.loginWithGoogle(accessToken)
        return UserResponseDto(
            userId = user.id,
            email = user.email,
            name = user.name,
            provider = user.provider,
            role = user.role
        )
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {

        // 1. 프론트가 토큰을 안 보냈을 경우 바로 입구컷
        require(!request.accessToken.isNullOrBlank()) { "액세스 토큰이 누락되었습니다." }

        // 2. 제공자(Provider)에 따라 네가 만든 서비스 로직 호출
        val user = when (request.provider.uppercase()) {
            "KAKAO" -> oauthService.loginWithKakao(request.accessToken)
            "GOOGLE" -> oauthService.loginWithGoogle(request.accessToken)
            else -> throw IllegalArgumentException("지원하지 않는 소셜 로그인 제공자입니다: ${request.provider}")
        }

        //JWT 발급
        val jwtTokens = jwtTokenProvider.createTokenResponse(user.id!!, user.role)

        //유저 정보와 토큰 리턴
        return ResponseEntity.ok(
            LoginResponse(
                userId = user.id!!,
                email = user.email,
                name = user.name,
                role = user.role,
                tokenInfo = jwtTokens,
                message = "${request.provider} 로그인 및 가입 완료!"
            )
        )

    }
    @ExceptionHandler(org.springframework.web.reactive.function.client.WebClientResponseException::class)
    fun handleOAuthError(e: org.springframework.web.reactive.function.client.WebClientResponseException): ResponseEntity<String> {
        println("🚨 소셜 로그인 토큰 인증 실패: ${e.statusCode}")
        return ResponseEntity.status(e.statusCode).body("유효하지 않거나 만료된 소셜 액세스 토큰입니다.")
    }
}

data class UserResponseDto(
    val userId: Long?,
    val email: String,
    val name: String,
    val provider: String,
    val role: String
)

