package com.hanyang.backend.security

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.user.JwtTokenProvider
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.util.Date

class JwtTokenProviderTest {
    private val secret = "estate-v1-test-secret-with-32-bytes-minimum"
    private val issuer = "gp-backend"
    private val provider = JwtTokenProvider(secret, issuer)
    private val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun `refresh and access tokens have distinct purposes`() {
        val tokens = provider.createTokenResponse(7, "GUEST")
        assertEquals(7L, provider.accessUserId(tokens.accessToken))
        assertEquals(7L, provider.refreshUserId(tokens.refreshToken))
        assertUnauthorized { provider.accessUserId(tokens.refreshToken) }
        assertUnauthorized { provider.refreshUserId(tokens.accessToken) }
    }

    @Test
    fun `expired wrong issuer and legacy tokens cannot authenticate`() {
        assertUnauthorized { provider.accessUserId(signed(expired = true)) }
        assertUnauthorized { provider.accessUserId(signed(tokenIssuer = "other-application")) }
        assertUnauthorized { provider.accessUserId(signed(purpose = null)) }
        assertUnauthorized { provider.accessUserId(signed(subject = "-1")) }
        assertUnauthorized { provider.accessUserId(signed(subject = "not-a-user-id")) }
        assertUnauthorized { provider.accessUserId(signed(withExpiry = false)) }
    }

    @Test
    fun `signature and malformed input are rejected`() {
        val token = JwtTokenProvider("different-test-secret-with-more-than-32-bytes", issuer).createAccessToken(7, "GUEST")
        assertUnauthorized { provider.accessUserId(token) }
        assertUnauthorized { provider.accessUserId("this.is.not-a-jwt") }
        assertUnauthorized { provider.accessUserId("") }
    }

    private fun signed(
        purpose: String? = "ACCESS",
        expired: Boolean = false,
        tokenIssuer: String = issuer,
        subject: String = "7",
        withExpiry: Boolean = true
    ): String {
        val now = Date()
        val builder = Jwts.builder().issuer(tokenIssuer).subject(subject).issuedAt(Date(now.time - 60_000))
        if (purpose != null) builder.claim("token_use", purpose)
        if (withExpiry) builder.expiration(Date(now.time + if (expired) -1000 else 60_000))
        return builder.signWith(key).compact()
    }

    private fun assertUnauthorized(action: () -> Unit) {
        val exception = assertThrows(ApiException::class.java, action)
        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
        assertEquals("INVALID_TOKEN", exception.code)
    }
}
