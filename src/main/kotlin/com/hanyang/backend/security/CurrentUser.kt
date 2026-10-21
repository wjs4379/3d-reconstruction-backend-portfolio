package com.hanyang.backend.security

import com.hanyang.backend.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** Only the verified application access token can establish this principal. */
data class AuthenticatedUser(val id: Long)

@Component
class CurrentUser {
    fun id(): Long {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? AuthenticatedUser
        if (authentication?.isAuthenticated != true || principal == null) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.")
        }
        return principal.id
    }
}
