package com.syeyoung.assistant.drive

import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.delay
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import org.w3c.dom.Element
import org.xml.sax.InputSource
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** A file or folder in the drive; [path] is relative to its root, folders end with "/". */
data class DriveEntry(
    val path: String, val name: String, val folder: Boolean, val size: Long, val modified: Instant?, val contentType: String?,
    /** The file's version (as an ETag): sent back as If-Match when writing, so a newer change isn't overwritten. */
    val etag: String? = null,
)

/** The drive's WebDAV server (RFC 4918), with basic auth. Paths are relative to its root, unencoded. */
@Component
class WebDav(props: AppProperties, web: WebClient.Builder) {
    private val config = props.drive
    private val base = config.url.trimEnd('/')
    private val http = web.defaultHeaders { it.setBasicAuth(config.username, config.password) }.build()

    /** A response read whole: its status, ETag and body. */
    private class Response(val status: Int, val etag: String?, val body: String)

    /** The folder's entries (not itself); null if it doesn't exist. */
    suspend fun list(folder: String): List<DriveEntry>? {
        val path = folder.trimEnd('/') + "/"
        val r = propfind(path, depth = 1)
        if (r.status == 404) return null
        check(r.status == 207) { "PROPFIND $path: HTTP ${r.status}" }
        val self = "/" + path.trimStart('/')
        return responses(r.body).mapNotNull { e ->
            val href = UriUtils.decode(e.text("href") ?: return@mapNotNull null, Charsets.UTF_8)
            val rel = href.removePrefix(URI(base).path.trimEnd('/'))
            if (rel == self || rel == self.trimEnd('/')) return@mapNotNull null
            val folder = e.getElementsByTagNameNS(DAV, "collection").length > 0
            val name = rel.trimEnd('/').substringAfterLast('/')
            DriveEntry(
                rel.trimStart('/').let { if (folder && !it.endsWith("/")) "$it/" else it }, name, folder,
                e.text("getcontentlength")?.toLongOrNull() ?: 0,
                e.text("getlastmodified")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() },
                e.text("getcontenttype"),
                version(e.text("getetag")),
            )
        }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    suspend fun exists(path: String): Boolean = propfind(path, depth = 0).status == 207

    /** Makes the folder and any missing parents. */
    suspend fun makeFolders(folder: String) {
        var at = ""
        for (part in folder.trim('/').split('/').filter { it.isNotEmpty() }) {
            at += "$part/"
            val code = status("MKCOL", at, emptyMap())
            // 405: it's there already.
            check(code == 201 || code == 405) { "MKCOL $at: HTTP $code" }
        }
    }

    /**
     * Writes a file (its folder must exist) from [body], streamed ([length] bytes, if known); with
     * [ifMatch] (an ETag), only if it's still that version; with [absent], only if there's none there
     * yet (If-None-Match: *). Its new ETag, if the server gives one.
     */
    suspend fun put(path: String, body: Flux<DataBuffer>, length: Long?, contentType: String?, ifMatch: String? = null, absent: Boolean = false): String? {
        // httpd marks a file's ETag weak for a second after it changes, and If-Match compares strongly:
        // a write right after the last one, based on it, would be refused. (The body is streamed, so it
        // can't be sent again.) Until that second is over, it waits.
        if (ifMatch != null && rawEtag(path) == "W/$ifMatch") delay(1100)
        val (code, etag) = http.put().uri(url(path))
            .headers { h ->
                contentType?.let { h.set(HttpHeaders.CONTENT_TYPE, it) }
                length?.let { h.contentLength = it }
                ifMatch?.let { h.ifMatch = listOf(it) }
                if (absent) h.ifNoneMatch = listOf("*")
            }
            .body(BodyInserters.fromDataBuffers(body))
            .awaitExchange { r -> r.releaseBody().awaitSingleOrNull(); r.statusCode().value() to r.headers().asHttpHeaders().eTag }
        if (code == 412) throw Changed(path)
        check(code in 200..299) { "PUT $path: HTTP $code" }
        return version(etag) ?: etag(path)
    }

    /** A write refused because the file changed since the version it was based on (or, if it was to be new, is there). */
    class Changed(path: String) : RuntimeException("$path changed since it was read")

    /**
     * A file's contents (streamed), status and headers; null if it doesn't exist. [range] (a Range
     * header) asks for part of it (206); [ifNoneMatch] (an ETag) for nothing if unchanged (304). The
     * body must be read or released.
     */
    suspend fun get(path: String, range: String? = null, ifNoneMatch: String? = null): ResponseEntity<Flux<DataBuffer>>? {
        val r = http.get().uri(url(path))
            .headers { h -> range?.let { h.set(HttpHeaders.RANGE, it) }; ifNoneMatch?.let { h.ifNoneMatch = listOf(it) } }
            .retrieve()
            // These are answers, not failures: the caller decides.
            .onStatus({ it.value() == 404 || it.value() == 416 }) { Mono.empty() }
            .toEntityFlux(DataBuffer::class.java)
            .awaitSingle()
        if (r.statusCode.value() == 404) { r.body?.doOnNext(DataBufferUtils::release)?.then()?.awaitSingleOrNull(); return null }
        return r
    }

    /** Moves (renames) a file or folder; fails if [to] exists. */
    suspend fun move(from: String, to: String) {
        val code = collection("MOVE", from, to, mapOf("Overwrite" to "F"))
        check(code == 201 || code == 204) { "MOVE $from → $to: HTTP $code" }
    }

    /** Copies a file, or a folder with everything in it; fails if something is already at [to]. */
    suspend fun copy(from: String, to: String) {
        val code = collection("COPY", from, to, mapOf("Overwrite" to "F", "Depth" to "infinity"))
        check(code == 201 || code == 204) { "COPY $from → $to: HTTP $code" }
    }

    suspend fun delete(path: String) {
        val code = collection("DELETE", path, null, emptyMap())
        check(code in 200..299 || code == 404) { "DELETE $path: HTTP $code" }
    }

    /**
     * A request on a file or folder (to [to], for MOVE and COPY): a folder addressed without its
     * "/" is redirected by the server, so it's asked again as one.
     */
    private suspend fun collection(method: String, path: String, to: String?, headers: Map<String, String>): Int {
        suspend fun ask(p: String, t: String?) = status(method, p, headers + (t?.let { mapOf("Destination" to url(it).toString()) } ?: emptyMap()))
        val code = ask(path, to)
        if (code != 301 || path.endsWith("/")) return code
        return ask("$path/", to?.let { if (it.endsWith("/")) it else "$it/" })
    }

    /** A file's current version (its ETag, strong); null if it has none or isn't there. */
    suspend fun etag(path: String): String? = version(rawEtag(path))

    /** The ETag as httpd gives it (weak while the file changed within the last second). */
    private suspend fun rawEtag(path: String): String? {
        val r = propfind(path, depth = 0)
        if (r.status != 207) return null
        return responses(r.body).firstOrNull()?.text("getetag")
    }

    /** A version as the drive's clients use it: httpd's "recently changed" weak mark taken off (the value is the same). */
    private fun version(etag: String?) = etag?.removePrefix("W/")

    /** [path]'s URL, each segment encoded. */
    private fun url(path: String): URI {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return UriComponentsBuilder.fromUriString(base).pathSegment(*segments.toTypedArray())
            .apply { if (path.endsWith("/") && segments.isNotEmpty()) path("/") }
            .build().encode().toUri()
    }

    private suspend fun propfind(path: String, depth: Int): Response =
        http.method(PROPFIND_METHOD).uri(url(path))
            .header("Depth", depth.toString())
            .contentType(MediaType.APPLICATION_XML)
            .bodyValue(PROPFIND)
            .awaitExchange { r -> Response(r.statusCode().value(), r.headers().asHttpHeaders().eTag, r.awaitBodyOrNull<String>() ?: "") }

    /** A request without a body whose status is all that matters. */
    private suspend fun status(method: String, path: String, headers: Map<String, String>): Int =
        http.method(HttpMethod.valueOf(method)).uri(url(path))
            .headers { h -> headers.forEach { (k, v) -> h.set(k, v) } }
            .awaitExchange { r -> r.releaseBody().awaitSingleOrNull(); r.statusCode().value() }

    private fun responses(xml: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val list = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).getElementsByTagNameNS(DAV, "response")
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.text(name: String): String? = getElementsByTagNameNS(DAV, name).let { if (it.length > 0) it.item(0).textContent else null }

    private companion object {
        val PROPFIND_METHOD: HttpMethod = HttpMethod.valueOf("PROPFIND")
        const val DAV = "DAV:"
        const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><d:getetag/></d:prop></d:propfind>"""
    }
}
