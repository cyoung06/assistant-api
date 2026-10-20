package com.syeyoung.assistant.dav

import com.syeyoung.assistant.config.AppProperties
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
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
class HttpCalDav(props: AppProperties, web: WebClient.Builder) : CalDav {
    private val config = props.dav
    private val base = config.url.trimEnd('/')
    private val home = "/${config.username}/"
    private val http = web.defaultHeaders { it.setBasicAuth(config.username, config.password) }.build()

    /** A response read whole: its status, ETag and body. */
    private class Response(val status: Int, val etag: String?, val body: String)

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
        if (response.status == 404) return null
        check(response.status == 200) { "GET $href: HTTP ${response.status}" }
        return DavObject(href, response.etag, response.body)
    }

    override suspend fun put(href: String, data: String, etag: String?): String? {
        val headers = mapOf("Content-Type" to "text/calendar; charset=utf-8") + (if (etag == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to etag))
        val response = request("PUT", href, data, headers)
        if (response.status == 412) throw DavConflictException(href)
        check(response.status in 200..299) { "PUT $href: HTTP ${response.status} ${response.body.take(200)}" }
        return response.etag
    }

    override suspend fun delete(href: String, etag: String?) {
        val response = request("DELETE", href, null, etag?.let { mapOf("If-Match" to it) } ?: emptyMap())
        if (response.status == 412) throw DavConflictException(href)
        check(response.status in 200..299 || response.status == 404) { "DELETE $href: HTTP ${response.status}" }
    }

    private suspend fun send(method: String, href: String, body: String?, headers: Map<String, String>, expect: Set<Int>): String {
        val response = request(method, href, body, headers + ("Content-Type" to "application/xml; charset=utf-8"))
        check(response.status in expect) { "$method $href: HTTP ${response.status} ${response.body.take(200)}" }
        return response.body
    }

    /**
     * [href] is a path as the server gives it (already encoded), so it's used as is. (A pooled
     * connection the server closed meanwhile is retried by the client itself, before anything is sent.)
     */
    private suspend fun request(method: String, href: String, body: String?, headers: Map<String, String>): Response =
        http.method(HttpMethod.valueOf(method)).uri(URI(base + href))
            .headers { h -> headers.forEach { (k, v) -> h.set(k, v) } }
            .apply { if (body != null) bodyValue(body) }
            .awaitExchange { r -> Response(r.statusCode().value(), r.headers().asHttpHeaders().eTag, r.awaitBodyOrNull<String>() ?: "") }

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
