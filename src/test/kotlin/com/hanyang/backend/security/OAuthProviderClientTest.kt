package com.hanyang.backend.security

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.user.OAuthProviderClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.ArrayDeque

class OAuthProviderClientTest {
    private val googleClientId = "our-web-client.apps.googleusercontent.com"
    private val kakaoAppId = "1234"

    @Test
    fun `unconfigured login provider returns service unavailable without external calls`() {
        val builder = WebClient.builder().exchangeFunction { throw AssertionError("Must not call a provider") }
        val client = OAuthProviderClient(builder, "", "")
        val google = assertThrows(ApiException::class.java) { client.googleProfile("provider-token") }
        val kakao = assertThrows(ApiException::class.java) { client.kakaoProfile("provider-token") }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, google.status)
        assertEquals("OAUTH_NOT_CONFIGURED", google.code)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, kakao.status)
    }

    @Test
    fun `google login verifies audience expiry verified email and profile identity`() {
        val client = client(
            googleInfo(),
            """{"id":"google-7","email":"owner@example.com","name":"Owner","verified_email":true,"picture":null}"""
        )
        val profile = client.googleProfile("provider-token")
        assertEquals("google-7", profile.providerId)
        assertEquals("owner@example.com", profile.email)
    }

    @Test
    fun `google rejects a different app expired token and mismatched profile`() {
        assertInvalid { client(googleInfo(audience = "another-app")).googleProfile("provider-token") }
        assertInvalid { client(googleInfo(expiresAt = Instant.now().epochSecond - 1)).googleProfile("provider-token") }
        assertInvalid { client(googleInfo(), """{"id":"other-user","email":"owner@example.com","name":"Owner","verified_email":true}""").googleProfile("provider-token") }
        assertInvalid { client(googleInfo(), """{"id":"google-7","email":"owner@example.com","name":"Owner","verified_email":false}""").googleProfile("provider-token") }
    }

    @Test
    fun `kakao validates app expiry and subject before creating a profile`() {
        val client = client(
            """{"id":7,"app_id":1234,"expires_in":3600}""",
            """{"id":7,"kakao_account":{"profile":{"nickname":"Owner"},"email":"owner@example.com","is_email_valid":true,"is_email_verified":true}}"""
        )
        val profile = client.kakaoProfile("provider-token")
        assertEquals("7", profile.providerId)
        assertEquals("owner@example.com", profile.email)
        assertInvalid { client("""{"id":7,"app_id":4321,"expires_in":3600}""").kakaoProfile("provider-token") }
        assertInvalid { client("""{"id":7,"app_id":1234,"expires_in":0}""").kakaoProfile("provider-token") }
        assertInvalid { client("""{"id":7,"app_id":1234,"expires_in":3600}""", """{"id":8}""").kakaoProfile("provider-token") }
    }

    @Test
    fun `missing kakao email is represented with reserved invalid address`() {
        val profile = client("""{"id":7,"app_id":1234,"expires_in":3600}""", """{"id":7}""").kakaoProfile("provider-token")
        assertEquals("7@kakao.invalid", profile.email)
    }

    private fun googleInfo(audience: String = googleClientId, expiresAt: Long = Instant.now().epochSecond + 3600) =
        """{"azp":"$audience","scope":"openid email profile","aud":"$audience","sub":"google-7","exp":"$expiresAt","expires_in":"3600","email":"owner@example.com","email_verified":"true"}"""

    private fun client(vararg jsonResponses: String): OAuthProviderClient {
        val queue = ArrayDeque(jsonResponses.toList())
        val exchange = ExchangeFunction {
            if (queue.isEmpty()) throw AssertionError("Unexpected extra provider request")
            Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(queue.removeFirst()).build())
        }
        return OAuthProviderClient(WebClient.builder().exchangeFunction(exchange), googleClientId, kakaoAppId)
    }

    private fun assertInvalid(action: () -> Unit) {
        val exception = assertThrows(ApiException::class.java, action)
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
        assertEquals("INVALID_SOCIAL_TOKEN", exception.code)
    }
}
