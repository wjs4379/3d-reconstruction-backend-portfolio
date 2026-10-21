package com.hanyang.backend.security

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.user.OAuthProviderClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.WebClient
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

class KakaoAuthorizationCodeTest {
    private val redirect = "https://estate.example/oauth/callback/kakao"
    private val secret = "non-production-client-secret-fixture"
    private val accessToken = "non-production-kakao-access-fixture"
    private val validToken = """{"token_type":"bearer","access_token":"$accessToken","expires_in":3600}"""
    private val appInfo = """{"id":7,"app_id":1234,"expires_in":3600}"""
    private val profile = """{"id":7,"kakao_account":{"profile":{"nickname":"Owner"}}}"""

    @Test
    fun `server exchanges an encoded code with secret and its fixed redirect before validating issuing app`() {
        NativeProvider(listOf(200 to validToken, 200 to appInfo, 200 to profile)).use { provider ->
            val client = client(provider.builder())
            assertEquals("7", client.kakaoCodeProfile("one-use+code/with=symbols").providerId)
            val token = provider.requests[0]
            assertEquals("https://kauth.kakao.com/oauth/token", token.original.toString())
            assertEquals("POST", token.method)
            assertNull(token.authorization)
            assertTrue(token.contentType?.startsWith("application/x-www-form-urlencoded") == true)
            assertEquals(mapOf("grant_type" to "authorization_code", "client_id" to "public-rest-key", "client_secret" to secret,
                "redirect_uri" to redirect, "code" to "one-use+code/with=symbols"), decode(token.body))
            assertEquals(listOf("/oauth/token", "/v1/user/access_token_info", "/v2/user/me"), provider.requests.map { it.original.path })
            provider.requests.drop(1).forEach { assertEquals("Bearer $accessToken", it.authorization) }
        }
    }

    @Test
    fun `unconfigured secret or invalid fixed redirect refuse provider calls`() {
        val builder = WebClient.builder().exchangeFunction { throw AssertionError("Unconfigured code exchange must not make provider calls") }
        for (client in listOf(client(builder, clientSecret = ""), client(builder, restKey = ""), client(builder, redirectUri = ""),
            client(builder, redirectUri = "http://public.example/oauth/callback/kakao"), client(builder, redirectUri = "https://user:password@estate.example/callback"),
            client(builder, redirectUri = "https://estate.example/callback?redirect=other"))) {
            val error = assertThrows(ApiException::class.java) { client.kakaoCodeProfile("code") }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status)
            assertEquals("OAUTH_NOT_CONFIGURED", error.code)
        }
    }

    @Test
    fun `provider denies an expired code without returning the code secret or provider response body`() {
        NativeProvider(listOf(400 to """{"error":"invalid_grant","error_description":"$secret and $accessToken"}""")).use { provider ->
            val error = assertThrows(ApiException::class.java) { client(provider.builder()).kakaoCodeProfile("expired-code") }
            assertEquals(HttpStatus.UNAUTHORIZED, error.status)
            assertEquals("INVALID_SOCIAL_TOKEN", error.code)
            assertFalse(error.message.orEmpty().contains(secret)); assertFalse(error.message.orEmpty().contains(accessToken))
            assertNull(error.cause)
            assertEquals(1, provider.requests.size)
        }
    }

    @Test
    fun `code-issued token from another app is rejected before loading user profile`() {
        NativeProvider(listOf(200 to validToken, 200 to """{"id":7,"app_id":9999,"expires_in":3600}""")).use { provider ->
            val error = assertThrows(ApiException::class.java) { client(provider.builder()).kakaoCodeProfile("code") }
            assertEquals(HttpStatus.UNAUTHORIZED, error.status)
            assertEquals(2, provider.requests.size)
        }
    }

    @Test
    fun `blank token or oversized native provider payload never creates a profile`() {
        for (body in listOf("""{"token_type":"bearer","access_token":"","expires_in":3600}""",
            """{"token_type":"bearer","access_token":"$accessToken","expires_in":3600,"padding":"${"x".repeat(100_000)}"}""")) {
            NativeProvider(listOf(200 to body)).use { provider ->
                val error = assertThrows(ApiException::class.java) { client(provider.builder()).kakaoCodeProfile("code") }
                assertTrue(error.status in setOf(HttpStatus.UNAUTHORIZED, HttpStatus.BAD_GATEWAY))
                if (body.length > 65536) assertEquals(HttpStatus.BAD_GATEWAY, error.status)
                assertEquals(1, provider.requests.size)
            }
        }
    }

    private fun client(builder: WebClient.Builder, restKey: String = "public-rest-key", clientSecret: String = secret, redirectUri: String = redirect) =
        OAuthProviderClient(builder, "google-client", "1234", restKey, clientSecret, redirectUri)

    private fun decode(body: String) = body.split('&').associate { value ->
        val parts = value.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
    }

    private data class Request(val original: URI, val method: String, val contentType: String?, val authorization: String?, val body: String)
    private class NativeProvider(responses: List<Pair<Int, String>>) : AutoCloseable {
        private val queue = ArrayDeque(responses)
        private val originals = ArrayDeque<URI>()
        val requests = mutableListOf<Request>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val original = synchronized(originals) { originals.removeFirst() }
                requests += Request(original, exchange.requestMethod, exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
                val result = queue.removeFirst()
                val bytes = result.second.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(result.first, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        fun builder(): WebClient.Builder = WebClient.builder().filter { request, next ->
            synchronized(originals) { originals.addLast(request.url()) }
            val rewritten = ClientRequest.from(request).url(URI("http://127.0.0.1:${server.address.port}${request.url().rawPath}")).build()
            next.exchange(rewritten)
        }
        override fun close() { server.stop(0) }
    }
}
