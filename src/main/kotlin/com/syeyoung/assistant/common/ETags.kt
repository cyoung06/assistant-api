package com.syeyoung.assistant.common

import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity

/** Parses an `If-Match` header carrying a version (`"7"`, `W/"7"` or `7`). Null when absent. */
fun parseIfMatch(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    val raw = header.trim().removePrefix("W/").trim('"')
    return raw.toLongOrNull() ?: throw ValidationException("If-Match must be a version number, got '$header'")
}

fun <T : Any> withETag(version: Long, body: T): ResponseEntity<T> =
    ResponseEntity.ok().header(HttpHeaders.ETAG, "\"$version\"").body(body)
