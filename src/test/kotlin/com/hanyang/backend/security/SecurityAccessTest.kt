package com.hanyang.backend.security

import com.hanyang.backend.common.ApiExceptionHandler
import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.OAuthController
import com.hanyang.backend.domain.user.OAuthService
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Optional

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@WebMvcTest(controllers = [SecurityAccessTest.ProbeController::class, OAuthController::class])
@Import(SecurityAccessTest.ProbeController::class, SecurityConfig::class, JwtTokenProvider::class, SecurityErrorWriter::class, CurrentUser::class, ApiExceptionHandler::class)
@TestPropertySource(properties = [
    "app.jwt.secret=estate-v1-test-secret-with-32-bytes-minimum",
    "app.jwt.issuer=gp-backend",
    "app.cors.allowed-origins=http://localhost:5173"
])
class SecurityAccessTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var tokens: JwtTokenProvider
    @MockitoBean private lateinit var users: UserRepository
    @MockitoBean private lateinit var oauth: OAuthService

    private lateinit var accessToken: String
    private lateinit var refreshToken: String

    @BeforeEach
    fun setUp() {
        val user = User(id = 7, email = "owner@example.com", name = "Owner", provider = "GOOGLE", providerId = "social-7", role = "GUEST")
        `when`(users.findById(7L)).thenReturn(Optional.of(user))
        `when`(oauth.loginWithGoogle("google-test-token")).thenReturn(user)
        val pair = tokens.createTokenResponse(7, "GUEST")
        accessToken = pair.accessToken
        refreshToken = pair.refreshToken
    }

    @Test
    fun `public listing reads are anonymous but managed reads and writes require authentication`() {
        mvc.perform(get("/api/v2/listings")).andExpect(status().isOk)
        mvc.perform(get("/api/v2/listings/1")).andExpect(status().isOk)
        mvc.perform(get("/api/v2/me/listings"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        mvc.perform(post("/api/v2/units"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
    }

    @Test
    fun `verified principal identifies user without trusting request ids`() {
        mvc.perform(get("/api/v2/me/listings").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ownerId").value(7))
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(7))
    }

    @Test
    fun `refresh token cannot be used as bearer and deleted user cannot authenticate`() {
        mvc.perform(get("/api/v2/me/listings").header(HttpHeaders.AUTHORIZATION, "Bearer $refreshToken"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
        `when`(users.findById(7L)).thenReturn(Optional.empty())
        mvc.perform(get("/api/v2/me/listings").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `legacy ownership and webhook APIs stay disabled even for authenticated callers`() {
        mvc.perform(post("/api/rooms").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        mvc.perform(post("/api/webhook").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `only refresh tokens can mint a replacement access token`() {
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("""{"refreshToken":"$refreshToken"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andExpect(jsonPath("$.expiresInSeconds").value(3600))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("""{"refreshToken":"$accessToken"}"""))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
    }

    @Test
    fun `social login issues application tokens and validates input`() {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"provider":"GOOGLE","accessToken":"google-test-token"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(7))
            .andExpect(jsonPath("$.tokenInfo.accessToken").isString)
            .andExpect(jsonPath("$.tokenInfo.refreshToken").isString)
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"provider":"GOOGLE","accessToken":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `anonymous Kakao code login returns only application identity and JWT tokens`() {
        val kakaoUser = User(id = 7, email = "owner@example.com", name = "Owner", provider = "KAKAO", providerId = "kakao-7", role = "GUEST")
        `when`(oauth.loginWithKakaoCode("single-use-code")).thenReturn(kakaoUser)
        mvc.perform(post("/api/auth/kakao/code").contentType(MediaType.APPLICATION_JSON)
            .content("""{"code":"single-use-code","redirectUri":"https://untrusted.example/callback","client_secret":"must-not-be-used"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(7))
            .andExpect(jsonPath("$.tokenInfo.accessToken").isString)
            .andExpect(jsonPath("$.tokenInfo.refreshToken").isString)
            .andExpect(jsonPath("$.client_secret").doesNotExist())
            .andExpect(jsonPath("$.code").doesNotExist())
    }

    @Test
    fun `Kakao code login validates missing and oversized input without bypassing authentication`() {
        for (input in listOf("", "x".repeat(8193))) {
            mvc.perform(post("/api/auth/kakao/code").contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"$input"}"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        mvc.perform(get("/api/auth/kakao/code"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `configured origin can preflight private requests and untrusted origin is rejected`() {
        mvc.perform(options("/api/v2/units")
            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
        mvc.perform(options("/api/v2/units")
            .header(HttpHeaders.ORIGIN, "https://untrusted.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .andExpect(status().isForbidden)
    }

    @RestController
    class ProbeController(private val currentUser: CurrentUser) {
        @GetMapping("/api/v2/listings", "/api/v2/listings/{id}")
        fun publicListings() = mapOf("visible" to true)

        @GetMapping("/api/v2/me/listings")
        fun mine() = mapOf("ownerId" to currentUser.id())

        @PostMapping("/api/v2/units", "/api/rooms", "/api/webhook")
        fun create() = mapOf("ownerId" to currentUser.id())
    }
}
