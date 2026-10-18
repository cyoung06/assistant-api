package com.syeyoung.assistant.dav

import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** A calendar collection (one per course / main tag), holding events and todos. */
data class DavCollection(val href: String, val name: String, val color: String?)

/** A stored iCalendar object. [href] is its path on the server; [etag] its current version. */
data class DavObject(val href: String, val etag: String?, val data: String)

class DavConflictException(href: String) : RuntimeException("$href was changed by someone else")

/** The CalDAV operations the interop layer needs (RFC 4791 over WebDAV, RFC 4918). */
interface CalDav {
    suspend fun collections(): List<DavCollection>
    /** Creates a calendar collection for events and todos. [slug] becomes the last path segment. */
    suspend fun createCollection(slug: String, name: String, color: String?): DavCollection
    suspend fun updateCollection(href: String, name: String?, color: String?)
    suspend fun deleteCollection(href: String)
    /** Every object in a collection, with data. */
    suspend fun objects(collectionHref: String): List<DavObject>
    suspend fun get(href: String): DavObject?
    /**
     * Stores [data] at [href]. [etag] null creates (fails if it exists); otherwise replaces only that
     * version. Returns the new etag (if the server reports one). Throws [DavConflictException].
     */
    suspend fun put(href: String, data: String, etag: String?): String?
    /** Deletes [href]; [etag] guards against deleting a newer version. Missing is fine. */
    suspend fun delete(href: String, etag: String?)
}

/** CalDAV over HTTP with basic auth, against one user's home on the server. */
@Component
class HttpCalDav(props: AppProperties) : CalDav {
    private val config = props.dav
    private val base = config.url.trimEnd('/')
    private val home = "/${config.username}/"
    private val auth = "Basic " + Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun collections(): List<DavCollection> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
              <d:prop><d:resourcetype/><d:displayname/><a:calendar-color/></d:prop>
            </d:propfind>"""
        val xml = send("PROPFIND", home, body, mapOf("Depth" to "1"), expect = setOf(207))
        return responses(xml).mapNotNull { r ->
            val isCalendar = r.find("DAV:", "resourcetype")?.getElementsByTagNameNS(CALDAV, "calendar")?.length?.let { it > 0 } == true
            if (!isCalendar) return@mapNotNull null
            val href = r.find("DAV:", "href")!!.textContent.trim()
            DavCollection(href, r.find("DAV:", "displayname")?.textContent?.trim().orEmpty().ifEmpty { href }, r.find(APPLE, "calendar-color")?.textContent?.trim()?.take(7))
        }
    }

    override suspend fun createCollection(slug: String, name: String, color: String?): DavCollection {
        val href = home + URLEncoder.encode(slug, Charsets.UTF_8) + "/"
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
              <d:set><d:prop>
                <d:displayname>${xmlEscape(name)}</d:displayname>
                ${color?.let { "<a:calendar-color>${it}FF</a:calendar-color>" } ?: ""}
                <c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set>
              </d:prop></d:set>
            </c:mkcalendar>"""
        send("MKCALENDAR", href, body, emptyMap(), expect = setOf(201))
        return DavCollection(href, name, color)
    }

    override suspend fun updateCollection(href: String, name: String?, color: String?) {
        val props = listOfNotNull(name?.let { "<d:displayname>${xmlEscape(it)}</d:displayname>" }, color?.let { "<a:calendar-color>${it}FF</a:calendar-color>" })
        if (props.isEmpty()) return
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propertyupdate xmlns:d="DAV:" xmlns:a="http://apple.com/ns/ical/"><d:set><d:prop>${props.joinToString("")}</d:prop></d:set></d:propertyupdate>"""
        send("PROPPATCH", href, body, emptyMap(), expect = setOf(207))
    }

    override suspend fun deleteCollection(href: String) {
        send("DELETE", href, null, emptyMap(), expect = setOf(200, 204, 404))
    }

    override suspend fun objects(collectionHref: String): List<DavObject> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop><d:getetag/><c:calendar-data/></d:prop>
              <c:filter><c:comp-filter name="VCALENDAR"/></c:filter>
            </c:calendar-query>"""
        val xml = send("REPORT", collectionHref, body, mapOf("Depth" to "1"), expect = setOf(207))
        return responses(xml).mapNotNull { r ->
            val data = r.find(CALDAV, "calendar-data")?.textContent ?: return@mapNotNull null
            DavObject(r.find("DAV:", "href")!!.textContent.trim(), r.find("DAV:", "getetag")?.textContent?.trim(), data)
        }
    }

    override suspend fun get(href: String): DavObject? {
        val response = request("GET", href, null, emptyMap())
        if (response.statusCode() == 404) return null
        check(response.statusCode() == 200) { "GET $href: HTTP ${response.statusCode()}" }
        return DavObject(href, response.headers().firstValue("ETag").orElse(null), response.body())
    }

    override suspend fun put(href: String, data: String, etag: String?): String? {
        val headers = mapOf("Content-Type" to "text/calendar; charset=utf-8") + (if (etag == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to etag))
        val (response, retried) = requestWithRetry("PUT", href, data, headers)
        // A retried create can find its own first attempt already stored.
        if (response.statusCode() == 412 && retried && etag == null && get(href)?.data?.let(::sameUid)?.invoke(data) == true) return null
        if (response.statusCode() == 412) throw DavConflictException(href)
        check(response.statusCode() in 200..299) { "PUT $href: HTTP ${response.statusCode()} ${response.body().take(200)}" }
        return response.headers().firstValue("ETag").orElse(null)
    }

    override suspend fun delete(href: String, etag: String?) {
        val response = request("DELETE", href, null, etag?.let { mapOf("If-Match" to it) } ?: emptyMap())
        if (response.statusCode() == 412) throw DavConflictException(href)
        check(response.statusCode() in 200..299 || response.statusCode() == 404) { "DELETE $href: HTTP ${response.statusCode()}" }
    }

    private suspend fun send(method: String, href: String, body: String?, headers: Map<String, String>, expect: Set<Int>): String {
        val response = request(method, href, body, headers + ("Content-Type" to "application/xml; charset=utf-8"))
        check(response.statusCode() in expect) { "$method $href: HTTP ${response.statusCode()} ${response.body().take(200)}" }
        return response.body()
    }

    /**
     * Sends the request, retrying once when the connection fails before a response arrives: the server
     * closes idle keep-alive connections, and the client may pick one that was just closed.
     */
    private suspend fun requestWithRetry(method: String, href: String, body: String?, headers: Map<String, String>): Pair<HttpResponse<String>, Boolean> =
        try {
            sendOnce(method, href, body, headers) to false
        } catch (e: java.io.IOException) {
            sendOnce(method, href, body, headers) to true
        }

    private suspend fun request(method: String, href: String, body: String?, headers: Map<String, String>): HttpResponse<String> =
        requestWithRetry(method, href, body, headers).first

    private fun sameUid(stored: String): (String) -> Boolean = { sent ->
        val uid = { text: String -> Regex("(?m)^UID:(.*)$").find(text.replace("\r", ""))?.groupValues?.get(1)?.trim() }
        uid(stored) != null && uid(stored) == uid(sent)
    }

    private suspend fun sendOnce(method: String, href: String, body: String?, headers: Map<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(base + href))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", auth)
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
    }

    private fun responses(xml: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val list = doc.getElementsByTagNameNS("DAV:", "response")
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.find(ns: String, name: String): Element? =
        getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        const val APPLE = "http://apple.com/ns/ical/"
    }
}
