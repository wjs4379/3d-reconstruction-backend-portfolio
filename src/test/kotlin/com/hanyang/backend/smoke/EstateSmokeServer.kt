package com.hanyang.backend.smoke

import com.hanyang.backend.BackendApplication
import com.hanyang.backend.domain.location.LocationProvider
import com.hanyang.backend.domain.location.ResolvedAddress
import com.hanyang.backend.common.ApiException
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import com.hanyang.backend.domain.user.OAuthProviderClient
import com.hanyang.backend.domain.user.SocialProfile
import org.mockito.Mockito
import org.springframework.boot.SpringApplication
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

/**
 * Test-classpath-only browser smoke server. This class is excluded from bootJar.
 * Fixture OAuth identities exist only on this test classpath; production uses real providers.
 * Its isolated H2 database is recreated on startup and all tokens use a fresh test secret.
 */
fun main(args: Array<String>) {
    require(args.isEmpty()) { "Smoke server does not accept application configuration overrides." }
    val tokenFile = Path.of(
        System.getProperty("estate.smoke.output",
            System.getenv("ESTATE_SMOKE_OUTPUT") ?: "build/estate-smoke/smoke-users.json")
    ).toAbsolutePath()
    val runtimeDirectory = requireNotNull(tokenFile.parent)
    Files.createDirectories(runtimeDirectory)
    val port = System.getenv("ESTATE_SMOKE_PORT")?.toIntOrNull() ?: 8080
    require(port in 1024..65535) { "Smoke server requires an unprivileged local port." }
    val origin = "http://127.0.0.1:$port"
    val randomSecret = ByteArray(48).also { SecureRandom().nextBytes(it) }
    val aiServiceKey = Base64.getEncoder().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })
    val properties = arrayOf(
        "--server.address=127.0.0.1",
        "--server.port=$port",
        "--spring.main.banner-mode=off",
        "--spring.datasource.url=jdbc:h2:mem:estate_browser_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "--spring.datasource.username=sa",
        "--spring.datasource.password=",
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.jpa.hibernate.ddl-auto=create-drop",
        "--spring.jpa.open-in-view=false",
        "--spring.jpa.properties.hibernate.default_batch_fetch_size=50",
        "--spring.jpa.properties.hibernate.show_sql=false",
        "--spring.flyway.enabled=false",
        "--app.jwt.secret=${Base64.getEncoder().encodeToString(randomSecret)}",
        "--app.jwt.issuer=estate-browser-smoke",
        "--app.cors.allowed-origins=http://127.0.0.1:5173,http://localhost:5173",
        "--app.media.mode=local",
        "--app.media.local-directory=${runtimeDirectory.resolve("media")}",
        "--app.media.public-base-url=$origin",
        "--app.ai.pipeline-enabled=true",
        "--app.ai.service-key=$aiServiceKey",
        "--app.ai.backend-base-url=$origin",
        "--ai.server.base-url=http://127.0.0.1:8000",
        "--outbox.worker.enabled=true",
        "--outbox.worker.max-concurrency=1",
        "--outbox.worker.batch-size=1",
        "--management.health.redis.enabled=false"
    )
    val context = SpringApplication(BackendApplication::class.java, EstateSmokeConfiguration::class.java).run(*properties)
    try {
        val users = context.getBean(UserRepository::class.java)
        val tokens = context.getBean(JwtTokenProvider::class.java)
        val owner = users.saveAndFlush(User(
            email = "owner@estate-smoke.invalid", name = "검증 게시자", provider = "KAKAO",
            providerId = "estate-smoke-owner", role = "HOST"
        ))
        val buyer = users.saveAndFlush(User(
            email = "buyer@estate-smoke.invalid", name = "검증 탐색자", provider = "KAKAO",
            providerId = "estate-smoke-buyer", role = "GUEST"
        ))
        fun fixture(user: User): Map<String, Any> {
            val userId = requireNotNull(user.id)
            val token = tokens.createTokenResponse(userId, user.role)
            return mapOf(
                "userId" to userId,
                "name" to user.name,
                "role" to user.role,
                "accessToken" to token.accessToken,
                "refreshToken" to token.refreshToken
            )
        }
        val output = mapOf("owner" to fixture(owner), "buyer" to fixture(buyer), "aiServiceKey" to aiServiceKey)
        // Tokens are written only into a local runtime artifact and never logged.
        Files.writeString(tokenFile, JsonMapper.builder().build().writeValueAsString(output), StandardCharsets.UTF_8)
        if (Files.getFileStore(tokenFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(tokenFile, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        println("SMOKE_USERS_FILE=$tokenFile")
        println("SMOKE_READY=$origin")
    } catch (failure: Exception) {
        context.close()
        throw failure
    }
}

/** Fixture resolver is confined to the test classpath; production always uses Kakao. */
@TestConfiguration
class EstateSmokeConfiguration {
    /** Existing /auth/login and JWT flow with fixture providers; excluded from bootJar. */
    @Bean @Primary
    fun smokeOAuthProviderClient(): OAuthProviderClient = Mockito.mock(OAuthProviderClient::class.java) { invocation ->
        if (invocation.method.name == "kakaoProfile") {
            when (invocation.getArgument<String>(0)) {
                "local-smoke-owner" -> SocialProfile("estate-smoke-owner", "검증 게시자", "owner@estate-smoke.invalid")
                "local-smoke-buyer" -> SocialProfile("estate-smoke-buyer", "검증 탐색자", "buyer@estate-smoke.invalid")
                else -> throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN", "올바른 로컬 테스트 계정을 선택해 주세요.")
            }
        } else if (invocation.method.name == "kakaoCodeProfile") {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED", "로컬 테스트 계정 환경은 실제 카카오 인증을 수행하지 않습니다.")
        } else Mockito.RETURNS_DEFAULTS.answer(invocation)
    }

    @Bean @Primary
    fun smokeLocationProvider(): LocationProvider = object : LocationProvider {
        override fun resolve(address: String): ResolvedAddress {
            if (address != "서울특별시 성동구 왕십리로 222") throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "SMOKE_ADDRESS_ONLY", "검증 서버에서는 지정한 예제 주소만 사용합니다.")
            return ResolvedAddress(address, address, "서울특별시 성동구 행당동 17", "04763",
                "1120010700", "서울특별시 성동구 행당동", BigDecimal("37.5570000"), BigDecimal("127.0450000"))
        }
    }
}
