package com.hanyang.backend.common

import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiErrorResponse(
    val code: String,
    val message: String
)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> =
        error(exception.status, exception.code, exception.message ?: "요청을 처리할 수 없습니다.")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HandlerMethodValidationException::class, ConstraintViolationException::class)
    fun handleInvalidParameter(): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 경로와 입력 값을 확인해 주세요.")

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleConcurrentUpdate(): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "다른 요청으로 내용이 변경되었습니다. 다시 조회해 주세요.")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataConflict(): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.CONFLICT, "DATA_CONFLICT", "기존 데이터와 충돌하는 요청입니다.")

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 자원을 찾을 수 없습니다.")


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
