package com.hanyang.backend.domain.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClient

@Service
class OAuthService(
    private val userRepository: UserRepository
) {
    private val kakaoClient = WebClient.create("https://kapi.kakao.com")
    private val googleClient = WebClient.create("https://www.googleapis.com") // 👈 구글 클라이언트 추가

    @Transactional
    fun loginWithKakao(accessToken: String): User {
        val kakaoUser = kakaoClient.get()
            .uri("/v2/user/me")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-type", "application/x-www-form-urlencoded;charset=utf-8")
            .retrieve()
            .bodyToMono(KakaoUserResponse::class.java)
            .block() ?: throw IllegalArgumentException("카카오 유저 정보를 불러오는데 실패했습니다.")

        val socialProfile = SocialProfile(
            providerId = kakaoUser.id.toString(),
            name = kakaoUser.kakao_account?.profile?.nickname ?: "카카오유저",
            email = kakaoUser.kakao_account?.email ?: "${kakaoUser.id}@kakao.com"
        )

        return getOrCreateUser("KAKAO", socialProfile)
    }

    // 🌟 [추가] 구글 소셜 로그인 비즈니스 로직
    @Transactional
    fun loginWithGoogle(accessToken: String): User {
        // 1. 구글 프로필 API 서버 호출 (전송받은 액세스 토큰 꼽아서 보냄)
        val googleUser = googleClient.get()
            .uri("/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .bodyToMono(GoogleUserResponse::class.java)
            .block() ?: throw IllegalArgumentException("구글 유저 정보를 불러오는데 실패했습니다.")

        // 2. 공통 프로필로 가공
        val socialProfile = SocialProfile(
            providerId = googleUser.id,
            name = googleUser.name,
            email = googleUser.email
        )

        // 3. DB 조회 후 가입 또는 로그인
        return getOrCreateUser("GOOGLE", socialProfile)
    }

    // 카카오/구글 공통 가입 처리를 위한 헬퍼 함수
    private fun getOrCreateUser(provider: String, profile: SocialProfile): User {
        return userRepository.findByProviderAndProviderId(provider, profile.providerId)
            .orElseGet {
                userRepository.save(
                    User(
                        email = profile.email,
                        name = profile.name,
                        provider = provider,
                        providerId = profile.providerId,
                        role = "GUEST"
                    )
                )
            }
    }
}