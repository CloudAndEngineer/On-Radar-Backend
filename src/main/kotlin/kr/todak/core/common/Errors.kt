package kr.todak.core.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** 명세서 에러 형식: HTTP 상태 + {"code": "...", "message": "..."} */
data class ErrorBody(val code: String, val message: String)

open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class NotFoundException(code: String = "NOT_FOUND", message: String = "찾을 수 없어요") :
    ApiException(HttpStatus.NOT_FOUND, code, message)

class BadRequestException(code: String = "INVALID_REQUEST", message: String) :
    ApiException(HttpStatus.BAD_REQUEST, code, message)

class ConflictException(code: String, message: String) :
    ApiException(HttpStatus.CONFLICT, code, message)

class UnauthorizedException(code: String = "UNAUTHORIZED", message: String = "로그인이 필요해요") :
    ApiException(HttpStatus.UNAUTHORIZED, code, message)

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(ErrorBody(e.code, e.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ErrorBody> {
        val msg = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ErrorBody("VALIDATION_ERROR", msg.ifBlank { "요청 값이 올바르지 않아요" }))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.badRequest().body(ErrorBody("INVALID_BODY", "요청 본문을 읽을 수 없어요 (JSON 형식·ENUM 철자를 확인해 주세요)"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun badParam(e: Exception) = ResponseEntity.badRequest().body(ErrorBody("INVALID_PARAMETER", e.message ?: "잘못된 값"))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException) =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorBody("UNAUTHORIZED", "${e.headerName} 헤더가 필요해요"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(e: NoResourceFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorBody("NOT_FOUND", "없는 경로예요"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorBody("METHOD_NOT_ALLOWED", e.message ?: ""))

    /** SSE 연결이 끊긴 뒤 쓰기 실패 — 응답할 곳이 없으므로 무시 */
    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun asyncGone(e: AsyncRequestNotUsableException) {
    }

    @ExceptionHandler(Exception::class)
    fun unknown(e: Exception): ResponseEntity<ErrorBody> {
        log.error("Unhandled error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorBody("INTERNAL_ERROR", "잠시 후 다시 시도해 주세요"))
    }
}
