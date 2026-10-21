package com.hanyang.backend.domain.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.hanyang.backend.common.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import java.net.URI
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

/** Validate the token's issuing application before using its user profile. */
@Component
class OAuthProviderClient(
    builder: WebClient.Builder,
    @param:Value("\${app.oauth.google.client-id:}") private val googleClientId: String,
    @param:Value("\${app.oauth.kakao.app-id:}") private val kakaoAppId: String,
    @param:Value("\${app.oauth.kakao.rest-api-key:}") private val kakaoRestApiKey: String = "",
    @param:Value("\${app.oauth.kakao.client-secret:}") private val kakaoClientSecret: String = "",
    @param:Value("\${app.oauth.kakao.redirect-uri:}") private val kakaoRedirectUri: String = ""
) {
    private val googleTokens = builder.clone().baseUrl("https://oauth2.googleapis.com").build()
    private val googleProfiles = builder.clone().baseUrl("https://www.googleapis.com").build()
    private val kakao = builder.clone().baseUrl("https://kapi.kakao.com").build()
    private val kakaoTokens = builder.clone().baseUrl("https://kauth.kakao.com")
        .codecs { it.defaultCodecs().maxInMemorySize(64 * 1024) }.build()

    fun googleProfile(accessToken: String): SocialProfile {
        requireConfigured(googleClientId)
        val info = await(googleTokens.get()
            .uri { it.path("/tokeninfo").queryParam("access_token", accessToken).build() }
            .retrieve().bodyToMono(GoogleAccessTokenInfo::class.java))
        if (info.aud != googleClientId || info.exp?.toLongOrNull()?.let { it > Instant.now().epochSecond } != true ||
            info.expires_in?.toLongOrNull()?.let { it > 0 } != true || info.sub.isNullOrBlank()) {
            throw invalidSocialToken()
        }
        val profile = await(googleProfiles.get().uri("/oauth2/v2/userinfo")
            .headers { it.setBearerAuth(accessToken) }
            .retrieve().bodyToMono(GoogleUserResponse::class.java))
        if (profile.id != info.sub || profile.email.isBlank() || profile.email != info.email ||
            profile.verified_email != true || info.email_verified != "true") {
            throw invalidSocialToken()
        }
        return SocialProfile(profile.id, profile.name.take(255), profile.email)
    }

    /** Client secret and fixed redirect URI are server settings, never browser input. */
    fun kakaoCodeProfile(code: String): SocialProfile {
        requireConfigured(kakaoRestApiKey)
        requireConfigured(kakaoClientSecret)
        requireConfigured(kakaoRedirectUri)
        if (kakaoAppId.toLongOrNull()?.takeIf { it > 0 } == null) throw notConfigured()
        val redirect = runCatching { URI(kakaoRedirectUri) }.getOrNull() ?: throw notConfigured()
        val secure = redirect.scheme == "https" || (redirect.scheme == "http" && redirect.host in setOf("localhost", "127.0.0.1", "[::1]", "::1"))
        if (!secure || redirect.host.isNullOrBlank() || redirect.userInfo != null || redirect.fragment != null || redirect.query != null) throw notConfigured()
        if (code.isBlank() || code.length > 8192) throw invalidSocialToken()
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", kakaoRestApiKey)
            add("client_secret", kakaoClientSecret)
            add("redirect_uri", kakaoRedirectUri)
            add("code", code)
        }
        val token = await(kakaoTokens.post().uri("/oauth/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form)).retrieve().bodyToMono(KakaoCodeTokenResponse::class.java))
        if (!token.token_type.equals("bearer", ignoreCase = true) || token.access_token.isNullOrBlank()
            || token.access_token.length > 8192 || (token.expires_in ?: 0) <= 0) throw invalidSocialToken()
        // Still verify the issuing app, expiry and subject using the established profile flow.
        return kakaoProfile(token.access_token)
    }

    fun kakaoProfile(accessToken: String): SocialProfile {
        requireConfigured(kakaoAppId)
        val configuredAppId = kakaoAppId.toLongOrNull()?.takeIf { it > 0 } ?: throw notConfigured()
        val info = await(kakao.get().uri("/v1/user/access_token_info")
            .headers { it.setBearerAuth(accessToken) }
            .retrieve().bodyToMono(KakaoAccessTokenInfo::class.java))
        if (info.app_id != configuredAppId || info.expires_in <= 0 || info.id <= 0) throw invalidSocialToken()
        val profile = await(kakao.get().uri("/v2/user/me")
            .headers { it.setBearerAuth(accessToken) }
            .retrieve().bodyToMono(KakaoUserResponse::class.java))
        if (profile.id != info.id) throw invalidSocialToken()
        val account = profile.kakao_account
        val verifiedEmail = account?.email?.takeIf {
            it.isNotBlank() && account.is_email_valid == true && account.is_email_verified == true
        }
        return SocialProfile(
            profile.id.toString(),
            (account?.profile?.nickname ?: "카카오유저").take(255),
            verifiedEmail ?: "${profile.id}@kakao.invalid"
        )
    }

    private fun <T : Any> await(response: Mono<T>): T {
        try {
            return response.timeout(Duration.ofSeconds(5)).block() ?: throw providerUnavailable()
        } catch (exception: WebClientResponseException) {
            if (exception.statusCode.value() in setOf(400, 401, 403)) throw invalidSocialToken()
            throw providerUnavailable()
        } catch (_: WebClientRequestException) {
            throw providerUnavailable()
        } catch (exception: RuntimeException) {
            if (exception is ApiException) throw exception
            if (exception.cause is TimeoutException) throw providerUnavailable()
            // Do not expose provider response bodies, request URIs, or access tokens in errors.
            throw providerUnavailable()
        }
    }

    private fun requireConfigured(value: String) {
        if (value.isBlank()) throw notConfigured()
    }

    private fun notConfigured() = ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED", "이 로그인 제공자가 아직 설정되지 않았습니다.")
    private fun invalidSocialToken() = ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN", "유효하지 않거나 이 서비스에 발급된 소셜 토큰이 아닙니다.")
    private fun providerUnavailable() = ApiException(HttpStatus.BAD_GATEWAY, "LOGIN_PROVIDER_UNAVAILABLE", "로그인 제공자에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.")
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleAccessTokenInfo(
    val aud: String? = null,
    val sub: String? = null,
    val exp: String? = null,
    val expires_in: String? = null,
    val email: String? = null,
    val email_verified: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoAccessTokenInfo(val id: Long, val expires_in: Long, val app_id: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoCodeTokenResponse(
    val token_type: String? = null,
    val access_token: String? = null,
    val expires_in: Long? = null
)
