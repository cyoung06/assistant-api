package com.syeyoung.assistant.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** An error with a stable machine-readable [code], returned as RFC 9457 problem+json. */
open class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

class NotFoundException(what: String, id: Any) : ApiException(HttpStatus.NOT_FOUND, "not_found", "$what $id not found")

class ValidationException(message: String) : ApiException(HttpStatus.BAD_REQUEST, "invalid", message)

class ConflictException(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)

class VersionMismatchException(val currentVersion: Long) :
    ApiException(HttpStatus.PRECONDITION_FAILED, "version_mismatch", "Resource was changed; current version is $currentVersion")

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ProblemDetail = ProblemDetail.forStatusAndDetail(e.status, e.message ?: e.code).apply {
        type = URI.create("urn:assistant:error:${e.code}")
        title = e.code
        if (e is VersionMismatchException) setProperty("currentVersion", e.currentVersion)
    }
}
