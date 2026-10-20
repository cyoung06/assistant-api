package com.syeyoung.assistant.source.plms

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.time.Duration
import java.time.Instant

class PlmsException(message: String) : RuntimeException(message)

/**
 * Talks to PLMS (Moodle) the way its web pages do: logs in through PLMS's own login form (not
 * POSTECH SSO), then calls Moodle's AJAX web service functions with the session key. The session is
 * kept between calls and renewed only when Moodle reports it expired.
 */
class PlmsClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val mapper: ObjectMapper,
    private val failures: LoginFailures,
    /** For the session: redirects not followed by it ([send] follows them, keeping each hop's cookies). */
    private val session: WebClient,
    /** For course files (no session: the web service token is in the URL). */
    private val files: WebClient,
) {
    /**
     * Where the last failed login is kept, so the wait after it outlasts restarts. [credentials]
     * identifies the username and password it failed with (a new password may log in at once).
     */
    interface LoginFailures {
        suspend fun last(): Pair<Instant, String>?
        suspend fun failed(at: Instant, credentials: String)
        suspend fun cleared()
    }

    /** A hash of the username and password, to tell whether a past failure was with these. */
    private val credentials = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$username\u0000$password".toByteArray()).joinToString("") { "%02x".format(it) }

    /** The session's cookies (the JDK's RFC 6265 store, used as just that). */
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val loginLock = Mutex()
    @Volatile private var sesskey: String? = null

    /** Calls a Moodle web service function, logging in first or again if needed. */
    suspend fun call(method: String, args: Map<String, Any?>): JsonNode {
        val key = sesskey ?: login(null)
        val first = ajax(key, method, args)
        if (first.errorCode in SESSION_ERRORS) {
            return ajax(login(key), method, args).dataOrThrow(method)
        }
        return first.dataOrThrow(method)
    }

    /** Deadline-type events (assignments, quizzes, …) between [from] and [to], all pages. */
    suspend fun actionEvents(from: Instant, to: Instant): List<JsonNode> {
        val events = mutableListOf<JsonNode>()
        var after: Long? = null
        while (true) {
            val args = buildMap<String, Any?> {
                put("timesortfrom", from.epochSecond)
                put("timesortto", to.epochSecond)
                put("limitnum", PAGE)
                after?.let { put("aftereventid", it) }
            }
            val page = call("core_calendar_get_action_events_by_timesort", args).path("events")
            page.forEach(events::add)
            if (page.size() < PAGE) return events
            after = page.last().path("id").asLong()
        }
    }

    /** Courses in progress: id and full name. */
    suspend fun courses(): List<JsonNode> =
        call("core_course_get_enrolled_courses_by_timeline_classification", mapOf("classification" to "inprogress")).path("courses").toList()

    @Volatile private var wsToken: String? = null

    /**
     * The token PLMS's own pages use for Moodle's REST web service (it's in every page for the
     * logged-in user); course contents (with file download links) are only offered there.
     */
    private suspend fun wsToken(): String = wsToken ?: (WS_TOKEN.find(page("$baseUrl/my/"))?.groupValues?.get(1)
        ?: throw PlmsException("PLMS pages carry no web service token")).also { wsToken = it }

    /** A course's sections and their activities, files included (core_course_get_contents). */
    suspend fun courseContents(courseId: Long): JsonNode = rest("core_course_get_contents", "courseid" to courseId.toString())

    /** An assignment as its page shows it: the instructions (HTML) and the files attached to them. */
    class Assignment(val introHtml: String, val attachments: List<Attachment>)
    class Attachment(val name: String, val url: String)

    private val assignmentPages = java.util.concurrent.ConcurrentHashMap<Long, Pair<Instant, Assignment>>()

    /**
     * The assignment with course module id [cmid], read from its page (the web service that lists
     * assignments isn't open to the token PLMS's pages carry). Kept for an hour.
     */
    suspend fun assignment(cmid: Long): Assignment {
        assignmentPages[cmid]?.let { (at, a) -> if (Instant.now() < at.plus(Duration.ofHours(1))) return a }
        return parseAssignment(page("$baseUrl/mod/assign/view.php?id=$cmid")).also { assignmentPages[cmid] = Instant.now() to it }
    }


    /** A Moodle REST web service call with the token PLMS's pages carry. */
    private suspend fun rest(function: String, vararg args: Pair<String, String>): JsonNode {
        repeat(2) {
            val url = UriComponentsBuilder.fromUriString("$baseUrl/webservice/rest/server.php")
                .apply { (listOf("wstoken" to wsToken(), "wsfunction" to function, "moodlewsrestformat" to "json") + args).forEach { (k, v) -> queryParam(k, v) } }
                .build().encode().toUri()
            val root = mapper.readTree(send(url).body)
            if (!root.isObject || !root.has("exception")) return root
            val code = root.path("errorcode").asString("")
            // Only a stale token (a new session issues a new one) is worth a fresh one; anything
            // else (no access to that function, a bad argument) would fail again after logging in.
            if (code !in TOKEN_ERRORS) throw PlmsException("PLMS $function: $code (${root.path("message").asString("")})")
            wsToken = null
            sesskey = null
        }
        throw PlmsException("PLMS $function: web service refused")
    }

    /** A course file's contents (streamed; read or release the body), from the download link course contents give. */
    suspend fun download(fileUrl: String): ResponseEntity<Flux<DataBuffer>> {
        // The token goes only to PLMS itself, whatever a page links to.
        val plms = URI(baseUrl)
        val target = URI(fileUrl)
        if (target.scheme != plms.scheme || !target.host.equals(plms.host, ignoreCase = true) || target.port != plms.port) {
            throw PlmsException("not a PLMS file: $fileUrl")
        }
        // Links from pages (not the web service) are for a browser session: the token works with the web service's.
        // (The link is encoded already, and the token is hex.)
        val url = UriComponentsBuilder.fromUriString(fileUrl.replace(Regex("""(?<!/webservice)/pluginfile\.php/"""), "/webservice/pluginfile.php/"))
            .queryParam("token", wsToken()).build(true).toUri()
        return files.get().uri(url).header(HttpHeaders.USER_AGENT, USER_AGENT).retrieve()
            .onStatus({ it.value() != 200 }) { r -> Mono.error(PlmsException("PLMS download: HTTP ${r.statusCode().value()}")) }
            .toEntityFlux(DataBuffer::class.java)
            .awaitSingle()
    }

    /** A PLMS page's HTML, as the logged-in user sees it. */
    suspend fun page(url: String): String {
        val key = sesskey ?: login(null)
        val first = send(URI(url))
        if (!first.url.path.startsWith("/login/")) return first.body
        login(key)
        return send(URI(url)).body
    }

    /**
     * Logs in, unless another caller already renewed the session [stale] was (then that one's used).
     * After a failed login, no more tries with the same credentials for a while (restarts too), so
     * PLMS doesn't lock the account.
     */
    private suspend fun login(stale: String?): String = loginLock.withLock {
        sesskey?.let { if (it != stale) return@withLock it }
        failures.last()?.let { (at, with) ->
            if (with == credentials && Instant.now() < at.plus(LOGIN_BACKOFF)) throw PlmsException("PLMS login failed recently; trying again after ${at.plus(LOGIN_BACKOFF)}")
        }
        cookies.cookieStore.removeAll()
        val form = send(URI("$baseUrl/login/index.php"))
        val token = LOGIN_TOKEN.find(form.body)?.groupValues?.get(1)
            ?: throw PlmsException("PLMS login page changed: no login token found")
        val response = send(URI("$baseUrl/login/index.php"), Body.Form(mapOf("anchor" to "", "logintoken" to token, "username" to username, "password" to password)))
        // A failed login stays on the login page; a successful one redirects away from it.
        if (response.url.path.startsWith("/login/")) {
            failures.failed(Instant.now(), credentials)
            throw PlmsException("PLMS login failed: check the username and password")
        }
        failures.cleared()
        val key = SESSKEY.find(response.body)?.groupValues?.get(1)
            ?: throw PlmsException("PLMS login succeeded but no session key was found")
        sesskey = key
        key
    }

    private class AjaxResult(val data: JsonNode?, val errorCode: String?, val message: String?) {
        fun dataOrThrow(method: String): JsonNode =
            data ?: throw PlmsException("PLMS $method failed: ${errorCode ?: "unknown error"}${message?.let { " ($it)" } ?: ""}")
    }

    private suspend fun ajax(key: String, method: String, args: Map<String, Any?>): AjaxResult {
        val body = mapper.writeValueAsString(listOf(mapOf("index" to 0, "methodname" to method, "args" to args)))
        val url = UriComponentsBuilder.fromUriString("$baseUrl/lib/ajax/service.php").queryParam("sesskey", key).queryParam("info", method).build().encode().toUri()
        val response = send(url, Body.Json(body))
        val root = runCatching { mapper.readTree(response.body) }.getOrNull()
            ?: return AjaxResult(null, "invalidresponse", "HTTP ${response.status}")
        // Moodle answers with an array of results, or a single error object for session problems.
        val result = if (root.isArray) root.path(0) else root
        if (result.path("error").asBoolean(false) || result.has("errorcode")) {
            val exception = result.path("exception").takeIf { !it.isMissingNode } ?: result
            return AjaxResult(null, exception.path("errorcode").asString(null), exception.path("message").asString(null))
        }
        return AjaxResult(result.path("data"), null, null)
    }

    /** What a request sends. */
    private sealed interface Body {
        class Form(val fields: Map<String, String>) : Body
        class Json(val text: String) : Body
    }

    /** A page as the browser ends up at it: where (after redirects), its status and its text. */
    private class Page(val url: URI, val status: Int, val body: String)

    /**
     * A request as a browser makes it: the session's cookies sent, the ones each response sets kept,
     * and redirects followed (as GETs, as browsers do after a POST).
     */
    private suspend fun send(url: URI, body: Body? = null): Page {
        var at = url
        var sending = body
        repeat(MAX_REDIRECTS) {
            val request = when (val b = sending) {
                null -> session.get().uri(at)
                is Body.Form -> session.post().uri(at).body(BodyInserters.fromFormData(LinkedMultiValueMap<String, String>().apply { b.fields.forEach { (k, v) -> add(k, v) } }))
                is Body.Json -> session.post().uri(at).contentType(MediaType.APPLICATION_JSON).bodyValue(b.text)
            }
            val (page, next) = request
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .headers { h -> cookies.get(at, emptyMap()).forEach { (k, v) -> h.addAll(k, v) } }
                .awaitExchange { r ->
                    val headers = r.headers().asHttpHeaders()
                    cookies.put(at, mapOf(HttpHeaders.SET_COOKIE to headers.getOrEmpty(HttpHeaders.SET_COOKIE)))
                    val location = headers.location.takeIf { r.statusCode().is3xxRedirection }
                    if (location != null) { r.releaseBody().awaitSingleOrNull(); null to at.resolve(location) }
                    else Page(at, r.statusCode().value(), r.awaitBodyOrNull<String>() ?: "") to null
                }
            if (page != null) return page
            at = next!!
            sending = null
        }
        throw PlmsException("PLMS: too many redirects from $url")
    }

    companion object {
        /** An assignment page's instructions and attached files. */
        fun parseAssignment(html: String): Assignment {
            val attachments = INTRO_ATTACHMENT.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.distinct().map { url ->
                Attachment(java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), Charsets.UTF_8), url)
            }.toList()
            // The instructions: what's in the intro box, without the attached files' list.
            val intro = element(html, html.indexOf("id=\"intro\""))?.let { box ->
                val tree = box.indexOf("assign_files_tree").takeIf { it >= 0 }?.let { element(box, it) }
                if (tree != null) box.replace(tree, "") else box
            }.orEmpty()
            return Assignment(intro, attachments)
        }

        /** The whole element (its tags too) whose opening tag contains [at]; null if there's none. */
        private fun element(html: String, at: Int): String? {
            if (at < 0) return null
            val open = html.lastIndexOf('<', at).takeIf { it >= 0 } ?: return null
            val tag = Regex("""<(\w+)""").find(html, open)?.groupValues?.get(1) ?: return null
            val tags = Regex("""<(/?)$tag\b[^>]*>""", RegexOption.IGNORE_CASE)
            var depth = 0
            for (m in tags.findAll(html, open)) {
                depth += if (m.groupValues[1].isEmpty()) 1 else -1
                if (depth == 0) return html.substring(open, m.range.last + 1)
            }
            return null
        }

        private const val PAGE = 50
        private const val MAX_REDIRECTS = 10
        private const val USER_AGENT = "assistant-sync/1.0"
        private val LOGIN_TOKEN = Regex("""name="logintoken" value="([^"]+)"""")
        private val SESSKEY = Regex(""""sesskey":"([^"]+)"""")
        private val WS_TOKEN = Regex("""MOODLE_WSTOKEN = "([0-9a-f]+)"""")
        private val TOKEN_ERRORS = setOf("invalidtoken")
        private val INTRO_ATTACHMENT = Regex("""href="([^"]*/pluginfile\.php/\d+/mod_assign/introattachment/[^"]+)"""")
        private val LOGIN_BACKOFF: Duration = Duration.ofMinutes(30)
        private val SESSION_ERRORS = setOf("servicerequireslogin", "invalidsesskey", "requireloginerror", "sitepolicynotagreed")
    }
}
