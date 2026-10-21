package com.hanyang.backend.domain.user

import com.hanyang.backend.common.ApiErrorResponse
import com.hanyang.backend.common.ApiException
import com.hanyang.backend.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.Locale

@RestController
@RequestMapping("/api/auth")
class OAuthController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val oauthService: OAuthService,
    private val userRepository: UserRepository,
    private val currentUser: CurrentUser
) {
    @PostMapping("/kakao")
    fun kakaoLogin(@RequestParam @NotBlank @Size(max = 8192) accessToken: String): UserResponseDto =
        oauthService.loginWithKakao(accessToken).toResponse()

    @PostMapping("/google")
    fun googleLogin(@RequestParam @NotBlank @Size(max = 8192) accessToken: String): UserResponseDto =
        oauthService.loginWithGoogle(accessToken).toResponse()

    @PostMapping("/login")
    fun login(@RequestBody @Valid request: LoginRequest): ResponseEntity<LoginResponse> {
        val provider = request.provider.uppercase(Locale.ROOT)
        val user = when (provider) {
            "KAKAO" -> oauthService.loginWithKakao(request.accessToken)
            "GOOGLE" -> oauthService.loginWithGoogle(request.accessToken)
            else -> throw ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PROVIDER", "지원하지 않는 로그인 제공자입니다.")
        }
        return issueLogin(user, provider)
    }

    @PostMapping("/kakao/code")
    fun kakaoCodeLogin(@RequestBody @Valid request: KakaoCodeLoginRequest): ResponseEntity<LoginResponse> =
        issueLogin(oauthService.loginWithKakaoCode(request.code), "KAKAO")

    private fun issueLogin(user: User, provider: String): ResponseEntity<LoginResponse> {
        val userId = requireNotNull(user.id)
        return ResponseEntity.ok(
            LoginResponse(
                userId = userId,
                email = user.email,
                name = user.name,
                role = user.role,
                tokenInfo = jwtTokenProvider.createTokenResponse(userId, user.role),
                message = "$provider 로그인 및 가입 완료!"
            )
        )
    }

    @GetMapping("/me")
    fun me(): UserResponseDto = userRepository.findById(currentUser.id()).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 사용자를 찾을 수 없습니다.")
    }.toResponse()

    @PostMapping("/refresh")
    fun refresh(@RequestBody @Valid request: RefreshTokenRequest): AccessTokenResponse {
        val userId = jwtTokenProvider.refreshUserId(request.refreshToken)
        val user = userRepository.findById(userId).orElseThrow {
            ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 사용자를 찾을 수 없습니다.")
        }
        // Reuse the original refresh token until its fixed expiry; no implicit lifetime extension.
        return AccessTokenResponse(accessToken = jwtTokenProvider.createAccessToken(userId, user.role))
    }

    @ExceptionHandler(WebClientResponseException::class)
    fun handleOAuthError(exception: WebClientResponseException): ResponseEntity<ApiErrorResponse> {
        val invalidToken = exception.statusCode.is4xxClientError
        val status = if (invalidToken) HttpStatus.UNAUTHORIZED else HttpStatus.BAD_GATEWAY
        val body = if (invalidToken) ApiErrorResponse("INVALID_SOCIAL_TOKEN", "유효하지 않거나 만료된 소셜 액세스 토큰입니다.")
            else ApiErrorResponse("LOGIN_PROVIDER_UNAVAILABLE", "로그인 제공자에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(WebClientRequestException::class)
    fun handleOAuthConnectionError(): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(HttpStatus.BAD_GATEWAY)
        .body(ApiErrorResponse("LOGIN_PROVIDER_UNAVAILABLE", "로그인 제공자에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."))

    private fun User.toResponse() = UserResponseDto(id, email, name, provider, role)
}

data class UserResponseDto(
    val userId: Long?,
    val email: String,
    val name: String,
    val provider: String,
    val role: String
)

data class RefreshTokenRequest(
    @field:NotBlank(message = "갱신 토큰이 필요합니다.")
    @field:Size(max = 8192)
    val refreshToken: String
)

data class AccessTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long = JwtTokenProvider.ACCESS_TOKEN_SECONDS
)
