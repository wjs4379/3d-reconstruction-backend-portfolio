package com.hanyang.backend.security

import com.hanyang.backend.common.ApiErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class SecurityErrorWriter(private val mapper: JsonMapper) {
    fun write(response: HttpServletResponse, status: HttpStatus, code: String, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        mapper.writeValue(response.outputStream, ApiErrorResponse(code, message))
    }
}
