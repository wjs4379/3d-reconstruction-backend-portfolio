package com.hanyang.backend.domain.user

// 카카오 유저 정보 응답 스펙
data class KakaoUserResponse(
    val id: Long,
    val connected_at: String?,
    val kakao_account: KakaoAccount?
) {
    data class KakaoAccount(
        val profile: Profile?,
        val email: String?
    ) {
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

data class GoogleUserResponse(
    val id: String,          // 구글의 고유 유저 ID
    val email: String,       // 이메일
    val name: String,        // 이름 (닉네임)
    val picture: String?     // 프로필 사진 URL
)

// 프론트가 백엔드(너)에게 쏴야 할 요청 스펙
data class LoginRequest(
    val provider: String,    // "KAKAO" 또는 "GOOGLE"
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