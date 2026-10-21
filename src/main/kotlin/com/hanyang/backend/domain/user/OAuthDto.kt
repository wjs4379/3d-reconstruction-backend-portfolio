package com.hanyang.backend.domain.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// 카카오 유저 정보 응답 스펙
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoUserResponse(
    val id: Long,
    val connected_at: String? = null,
    val kakao_account: KakaoAccount? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class KakaoAccount(
        val profile: Profile? = null,
        val email: String? = null,
        val is_email_valid: Boolean? = null,
        val is_email_verified: Boolean? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Profile(
            val nickname: String?
        )
    }
}

// 서비스 내부에서 공통으로 쓸 소셜 유저 프로필 정보
data class SocialProfile(
    val providerId: String,
    val name: String,
    val email: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleUserResponse(
    val id: String,          // 구글의 고유 유저 ID
    val email: String,       // 이메일
    val name: String,        // 이름 (닉네임)
    val picture: String? = null,    // 프로필 사진 URL
    val verified_email: Boolean? = null
)

// 프론트가 백엔드(너)에게 쏴야 할 요청 스펙
data class LoginRequest(
    @field:NotBlank(message = "로그인 제공자를 입력해 주세요.")
    @field:Size(max = 16)
    val provider: String,    // "KAKAO" 또는 "GOOGLE"
    @field:NotBlank(message = "소셜 액세스 토큰이 필요합니다.")
    @field:Size(max = 8192)
    val accessToken: String  // 프론트가 카카오/구글에서 갓 받아온 진짜 액세스 토큰
)

// 백엔드가 프론트에게 내려줄 응답 스펙
data class LoginResponse(
    val userId: Long,
    val email: String,       // 프론트가 화면에 "OOO님 환영합니다" 띄울 수 있게 내려줌
    val name: String,
    val role: String,        // "GUEST" 등 권한 정보
    val tokenInfo: JwtTokenDto,
    val message: String
)


data class JwtTokenDto(
    val accessToken: String,
    val refreshToken: String
)

// Browser sends only the authorization code; redirect URI/client secret stay on the server.
data class KakaoCodeLoginRequest(
    @field:NotBlank(message = "카카오 인가 코드가 필요합니다.")
    @field:Size(max = 8192)
    val code: String
)
