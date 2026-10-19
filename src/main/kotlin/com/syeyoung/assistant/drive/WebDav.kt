package com.syeyoung.assistant.drive

import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** A file or folder in the drive; [path] is relative to its root, folders end with "/". */
data class DriveEntry(
    val path: String, val name: String, val folder: Boolean, val size: Long, val modified: Instant?, val contentType: String?,
    /** The file's version (as an ETag): sent back as If-Match when writing, so a newer change isn't overwritten. */
    val etag: String? = null,
)

/** The drive's WebDAV server (RFC 4918), with basic auth. Paths are relative to its root, unencoded. */
@Component
class WebDav(props: AppProperties) {
    private val config = props.drive
    private val base = config.url.trimEnd('/')
    private val auth = "Basic " + Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build()

    /** The folder's entries (not itself); null if it doesn't exist. */
    suspend fun list(folder: String): List<DriveEntry>? {
        val path = folder.trimEnd('/') + "/"
        val r = send("PROPFIND", path, ofString(PROPFIND), mapOf("Depth" to "1", "Content-Type" to "application/xml"), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() == 404) return null
        check(r.statusCode() == 207) { "PROPFIND $path: HTTP ${r.statusCode()}" }
        val self = "/" + path.trimStart('/')
        return responses(r.body()).mapNotNull { e ->
            val href = decode(e.text("href") ?: return@mapNotNull null)
            val rel = href.removePrefix(URI(base).path.trimEnd('/'))
            if (rel == self || rel == self.trimEnd('/')) return@mapNotNull null
            val folder = e.getElementsByTagNameNS(DAV, "collection").length > 0
            val name = rel.trimEnd('/').substringAfterLast('/')
            DriveEntry(
                rel.trimStart('/').let { if (folder && !it.endsWith("/")) "$it/" else it }, name, folder,
                e.text("getcontentlength")?.toLongOrNull() ?: 0,
                e.text("getlastmodified")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() },
                e.text("getcontenttype"),
                e.text("getetag"),
            )
        }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    suspend fun exists(path: String): Boolean =
        send("PROPFIND", path, ofString(PROPFIND), mapOf("Depth" to "0", "Content-Type" to "application/xml"), HttpResponse.BodyHandlers.discarding()).statusCode() == 207

    /** Makes the folder and any missing parents. */
    suspend fun makeFolders(folder: String) {
        var at = ""
        for (part in folder.trim('/').split('/').filter { it.isNotEmpty() }) {
            at += "$part/"
            val code = send("MKCOL", at, HttpRequest.BodyPublishers.noBody(), emptyMap(), HttpResponse.BodyHandlers.discarding()).statusCode()
            // 405: it's there already.
            check(code == 201 || code == 405) { "MKCOL $at: HTTP $code" }
        }
    }

    /** Writes a file (its folder must exist). */
    /** Writes a file; with [ifMatch] (an ETag), only if it's still that version. Its new ETag, if the server gives one. */
    suspend fun put(path: String, body: InputStream, length: Long?, contentType: String?, ifMatch: String? = null): String? {
        val publisher = if (length != null) HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream { body }, length)
        else HttpRequest.BodyPublishers.ofInputStream { body }
        val headers = buildMap { contentType?.let { put("Content-Type", it) }; ifMatch?.let { put("If-Match", it) } }
        val r = send("PUT", path, publisher, headers, HttpResponse.BodyHandlers.discarding())
        if (r.statusCode() == 412) throw Changed(path)
        check(r.statusCode() in 200..299) { "PUT $path: HTTP ${r.statusCode()}" }
        return r.headers().firstValue("ETag").orElse(null) ?: etag(path)
    }

    /** A write refused because the file changed since the version it was based on. */
    class Changed(path: String) : RuntimeException("$path changed since it was read")

    /**
     * A file's contents and type; null if it doesn't exist. [range] (a Range header) asks for part
     * of it (206); [ifNoneMatch] (an ETag) for nothing if unchanged (304).
     */
    suspend fun get(path: String, range: String? = null, ifNoneMatch: String? = null): HttpResponse<InputStream>? {
        val headers = buildMap { range?.let { put("Range", it) }; ifNoneMatch?.let { put("If-None-Match", it) } }
        val r = send("GET", path, HttpRequest.BodyPublishers.noBody(), headers, HttpResponse.BodyHandlers.ofInputStream())
        if (r.statusCode() == 404) { r.body().close(); return null }
        check(r.statusCode() in setOf(200, 206, 304, 416)) { r.body().close(); "GET $path: HTTP ${r.statusCode()}" }
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
        suspend fun ask(p: String, t: String?) = send(method, p, HttpRequest.BodyPublishers.noBody(), headers + (t?.let { mapOf("Destination" to url(it)) } ?: emptyMap()), HttpResponse.BodyHandlers.discarding()).statusCode()
        val code = ask(path, to)
        if (code != 301 || path.endsWith("/")) return code
        return ask("$path/", to?.let { if (it.endsWith("/")) it else "$it/" })
    }

    /** A file's current ETag; null if it has none or isn't there. */
    suspend fun etag(path: String): String? {
        val r = send("PROPFIND", path, ofString(PROPFIND), mapOf("Depth" to "0", "Content-Type" to "application/xml"), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() != 207) return null
        return responses(r.body()).firstOrNull()?.text("getetag")
    }

    private fun url(path: String) = base + "/" + path.trimStart('/').split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

    private fun ofString(s: String) = HttpRequest.BodyPublishers.ofString(s)

    private suspend fun <T> send(method: String, path: String, body: HttpRequest.BodyPublisher, headers: Map<String, String>, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val b = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofMinutes(10)).header("Authorization", auth).method(method, body)
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.sendAsync(b.build(), handler).await()
    }

    private fun decode(href: String) = URLDecoder.decode(href.replace("+", "%2B"), Charsets.UTF_8)

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
        const val DAV = "DAV:"
        const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><d:getetag/></d:prop></d:propfind>"""
    }
}
