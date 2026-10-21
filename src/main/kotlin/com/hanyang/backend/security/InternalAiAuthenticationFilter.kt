package com.hanyang.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Internal AI callbacks and source-photo fetches require a separate server credential. */
class InternalAiAuthenticationFilter(private val env: Environment, private val errors: SecurityErrorWriter) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.removePrefix(request.contextPath).startsWith("/api/internal/ai/")
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val expected = env.getProperty("app.ai.service-key", "").toByteArray(StandardCharsets.UTF_8)
        if (!env.getProperty("app.ai.pipeline-enabled",Boolean::class.java,false) || expected.size < 32) {
            errors.write(response,HttpStatus.SERVICE_UNAVAILABLE,"AI_PIPELINE_NOT_CONFIGURED","AI 연결이 설정되지 않았습니다.")
            return
        }
        val headers = request.getHeaders("X-AI-Service-Key").toList()
        val supplied = headers.singleOrNull()
        if (supplied == null || supplied.length > 512 || !MessageDigest.isEqual(expected,supplied.toByteArray(StandardCharsets.UTF_8))) {
            errors.write(response,HttpStatus.UNAUTHORIZED,"AI_SERVICE_UNAUTHORIZED","AI 서비스 인증에 실패했습니다.")
            return
        }
        chain.doFilter(request,response)
    }
}
