package com.hanyang.backend.domain.user

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @param:Value("\${app.jwt.secret}")
    private val secretKeyString: String
) {
    init {
        require(secretKeyString.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "JWT_SECRET은 32바이트 이상이어야 합니다."
        }
    }

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secretKeyString.toByteArray(StandardCharsets.UTF_8))

    private val accessTokenValidityInMilliseconds: Long = 60 * 60 * 1000 // 1시간
    private val refreshTokenValidityInMilliseconds: Long = 7 * 24 * 60 * 60 * 1000 // 7일

    // 🔑 AccessToken, RefreshToken 쌍으로 발급
    fun createTokenResponse(userId: Long, role: String): JwtTokenDto {
        val now = Date()

        val accessToken = Jwts.builder()
            .subject(userId.toString())
            .claim("role", role)
            .issuedAt(now)
            .expiration(Date(now.time + accessTokenValidityInMilliseconds))
            .signWith(secretKey)
            .compact()

        val refreshToken = Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(Date(now.time + refreshTokenValidityInMilliseconds))
            .signWith(secretKey)
            .compact()

        return JwtTokenDto(
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }
}
