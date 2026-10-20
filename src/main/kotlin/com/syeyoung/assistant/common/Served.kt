package com.syeyoung.assistant.common

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * Stored files are served from the app's own address, so one that's a page (HTML, SVG, …) must run
 * nothing there: only PDFs and (non-SVG) images, video and audio are shown as themselves, for the
 * browser's viewers; anything else is sandboxed (no script, no same-origin access) and downloaded.
 */
fun <B : ResponseEntity.HeadersBuilder<B>> B.servedSafely(type: String, filename: String? = null): B {
    val t = runCatching { MediaType.parseMediaType(type) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM)
    val shown = t.equalsTypeAndSubtype(MediaType.APPLICATION_PDF) ||
        (t.type == "image" && t.subtype != "svg+xml") || t.type == "video" || t.type == "audio"
    header("X-Content-Type-Options", "nosniff")
    if (!shown) header("Content-Security-Policy", "sandbox")
    if (filename != null || !shown) {
        val name = filename?.let { "; filename*=UTF-8''" + java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") } ?: ""
        header("Content-Disposition", (if (shown) "inline" else "attachment") + name)
    }
    return this
}
