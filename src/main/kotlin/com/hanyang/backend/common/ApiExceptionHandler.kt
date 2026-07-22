package com.hanyang.backend.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiErrorResponse(
    val code: String,
    val message: String
)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException
    ): ResponseEntity<ApiErrorResponse> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "요청 값을 확인해 주세요."

        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(): ResponseEntity<ApiErrorResponse> {
        return error(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST_BODY",
            "요청 본문 형식을 확인해 주세요."
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        exception: IllegalArgumentException
    ): ResponseEntity<ApiErrorResponse> {
        return error(
            HttpStatus.BAD_REQUEST,
            "INVALID_ARGUMENT",
            exception.message ?: "요청 값을 확인해 주세요."
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(
        exception: IllegalStateException
    ): ResponseEntity<ApiErrorResponse> {
        return error(
            HttpStatus.CONFLICT,
            "INVALID_MODEL_STATE",
            exception.message ?: "현재 작업 상태에서는 요청을 처리할 수 없습니다."
        )
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String
    ): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity
            .status(status)
            .body(ApiErrorResponse(code, message))
    }
}
