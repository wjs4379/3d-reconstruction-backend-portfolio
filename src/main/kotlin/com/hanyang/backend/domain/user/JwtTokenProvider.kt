package com.hanyang.backend.domain.user

import com.hanyang.backend.common.ApiException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @param:Value("\${app.jwt.secret}") private val secretKeyString: String,
    @param:Value("\${app.jwt.issuer:gp-backend}") private val issuer: String = "gp-backend"
) {
    init {
        require(secretKeyString.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "JWT_SECRET은 32바이트 이상이어야 합니다."
        }
        require(issuer.isNotBlank()) { "JWT issuer must not be blank." }
    }

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secretKeyString.toByteArray(StandardCharsets.UTF_8))

    fun createTokenResponse(userId: Long, role: String): JwtTokenDto = JwtTokenDto(
        accessToken = createToken(userId, role, "ACCESS", ACCESS_TOKEN_SECONDS),
        refreshToken = createToken(userId, null, "REFRESH", REFRESH_TOKEN_SECONDS)
    )

    fun createAccessToken(userId: Long, role: String): String =
        createToken(userId, role, "ACCESS", ACCESS_TOKEN_SECONDS)

    fun accessUserId(token: String): Long = verifiedUserId(token, "ACCESS")

    fun refreshUserId(token: String): Long = verifiedUserId(token, "REFRESH")

    private fun createToken(userId: Long, role: String?, purpose: String, seconds: Long): String {
        require(userId > 0) { "사용자 ID가 올바르지 않습니다." }
        val now = Date()
        val builder = Jwts.builder()
            .issuer(issuer)
            .subject(userId.toString())
            .claim("token_use", purpose)
            .issuedAt(now)
            .expiration(Date(now.time + seconds * 1000))
        if (role != null) builder.claim("role", role)
        return builder.signWith(secretKey).compact()
    }

    private fun verifiedUserId(token: String, purpose: String): Long {
        try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .require("token_use", purpose)
                .build()
                .parseSignedClaims(token)
                .payload
            // A signed token without an expiry is not an application token.
            if (claims.expiration == null || claims.issuedAt == null) throw invalidToken()
            return claims.subject?.toLongOrNull()?.takeIf { it > 0 } ?: throw invalidToken()
        } catch (_: JwtException) {
            throw invalidToken()
        } catch (_: IllegalArgumentException) {
            throw invalidToken()
        }
    }

    private fun invalidToken() = ApiException(
        HttpStatus.UNAUTHORIZED,
        "INVALID_TOKEN",
        "유효하지 않거나 만료된 인증 토큰입니다."
    )

    companion object {
        const val ACCESS_TOKEN_SECONDS = 3600L
        const val REFRESH_TOKEN_SECONDS = 7 * 24 * 60 * 60L
    }
}
