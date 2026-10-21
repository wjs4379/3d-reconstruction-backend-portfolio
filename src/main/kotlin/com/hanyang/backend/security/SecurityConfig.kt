package com.hanyang.backend.security

import com.hanyang.backend.domain.user.JwtTokenProvider
import com.hanyang.backend.domain.user.UserRepository
import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val tokenProvider: JwtTokenProvider,
    private val users: UserRepository,
    private val errors: SecurityErrorWriter,
    private val env: org.springframework.core.env.Environment,
    @param:Value("\${app.cors.allowed-origins:http://localhost:5173}") private val allowedOrigins: String
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // Tokens arrive in an explicit Authorization header, never in an authentication cookie.
        http.csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    errors.write(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.")
                }
                it.accessDeniedHandler { _, response, _ ->
                    errors.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "이 작업에 대한 권한이 없습니다.")
                }
            }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/kakao", "/api/auth/kakao/code", "/api/auth/google", "/api/auth/refresh").permitAll()
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v2/listings", "/api/v2/listings/*", "/api/v2/listings/*/assets/*").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/internal/ai/webhook").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/internal/ai/assets/*/content").permitAll()
                it.requestMatchers("/api/auth/me", "/api/v2/**").authenticated()
                // Legacy APIs trust body ownerId/userId. They remain unavailable until migrated.
                it.anyRequest().denyAll()
            }
            .addFilterBefore(InternalAiAuthenticationFilter(env, errors), UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(JwtAuthenticationFilter(tokenProvider, users, errors), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val origins = allowedOrigins.split(',').map { it.trim() }.filter { it.isNotBlank() }
        require(origins.isNotEmpty() && origins.none { it == "*" }) { "명시적인 CORS 허용 출처를 설정해 주세요." }
        val configuration = CorsConfiguration().apply {
            setAllowedOrigins(origins)
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "If-Match", "Idempotency-Key")
            exposedHeaders = listOf("Location")
            allowCredentials = false
            maxAge = 3600L
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", configuration) }
    }
}
