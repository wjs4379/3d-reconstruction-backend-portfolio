package com.hanyang.backend.security

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val tokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,
    private val errors: SecurityErrorWriter
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authorization == null) {
            filterChain.doFilter(request, response)
            return
        }
        try {
            if (authorization.length > 8200 || !authorization.startsWith("Bearer ", ignoreCase = true) ||
                request.getHeaders(HttpHeaders.AUTHORIZATION).toList().size != 1) {
                throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Bearer 인증 토큰을 확인해 주세요.")
            }
            val userId = tokenProvider.accessUserId(authorization.substring(7).trim())
            val user = userRepository.findById(userId).orElseThrow {
                ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 사용자를 찾을 수 없습니다.")
            }
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = UsernamePasswordAuthenticationToken(
                AuthenticatedUser(userId), null, listOf(SimpleGrantedAuthority("ROLE_${user.role}"))
            )
            SecurityContextHolder.setContext(context)
        } catch (exception: ApiException) {
            SecurityContextHolder.clearContext()
            errors.write(response, exception.status, exception.code, exception.message ?: "인증에 실패했습니다.")
            return
        }
        filterChain.doFilter(request, response)
    }
}
