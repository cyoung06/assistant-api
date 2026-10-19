package com.syeyoung.assistant.source.plms

import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val http = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val loginLock = Mutex()
    @Volatile private var sesskey: String? = null

    /** Calls a Moodle web service function, logging in first or again if needed. */
    suspend fun call(method: String, args: Map<String, Any?>): JsonNode {
        val key = sesskey ?: login()
        val first = ajax(key, method, args)
        if (first.errorCode in SESSION_ERRORS) {
            return ajax(login(), method, args).dataOrThrow(method)
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
            val q = formEncode("wstoken" to wsToken(), "wsfunction" to function, "moodlewsrestformat" to "json", *args)
            val root = mapper.readTree(send(HttpRequest.newBuilder(URI("$baseUrl/webservice/rest/server.php?$q")).GET()).body())
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

    /** A course file's contents, from the download link course contents give. */
    suspend fun download(fileUrl: String): HttpResponse<java.io.InputStream> {
        // Links from pages (not the web service) are for a browser session: the token works with the web service's.
        val url = fileUrl.replace(Regex("""(?<!/webservice)/pluginfile\.php/"""), "/webservice/pluginfile.php/").let { it + if ('?' in it) "&" else "?" } + "token=" + URLEncoder.encode(wsToken(), Charsets.UTF_8)
        val r = http.sendAsync(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).header("User-Agent", USER_AGENT).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        ).await()
        if (r.statusCode() != 200) { r.body().close(); throw PlmsException("PLMS download: HTTP ${r.statusCode()}") }
        return r
    }

    /** A PLMS page's HTML, as the logged-in user sees it. */
    suspend fun page(url: String): String {
        if (sesskey == null) login()
        val first = send(HttpRequest.newBuilder(URI(url)).GET())
        if (!first.uri().path.startsWith("/login/")) return first.body()
        login()
        return send(HttpRequest.newBuilder(URI(url)).GET()).body()
    }

    /** When a login last failed: no more tries for a while, so PLMS doesn't lock the account. */
    @Volatile private var failedAt: Instant? = null

    private suspend fun login(): String = loginLock.withLock {
        failedAt?.let { if (Instant.now() < it.plus(LOGIN_BACKOFF)) throw PlmsException("PLMS login failed recently; trying again after ${it.plus(LOGIN_BACKOFF)}") }
        cookies.cookieStore.removeAll()
        val form = send(HttpRequest.newBuilder(URI("$baseUrl/login/index.php")).GET())
        val token = LOGIN_TOKEN.find(form.body())?.groupValues?.get(1)
            ?: throw PlmsException("PLMS login page changed: no login token found")
        val response = send(
            HttpRequest.newBuilder(URI("$baseUrl/login/index.php"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode("anchor" to "", "logintoken" to token, "username" to username, "password" to password))),
        )
        // A failed login stays on the login page; a successful one redirects away from it.
        if (response.uri().path.startsWith("/login/")) {
            failedAt = Instant.now()
            throw PlmsException("PLMS login failed: check the username and password")
        }
        failedAt = null
        val key = SESSKEY.find(response.body())?.groupValues?.get(1)
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
        val response = send(
            HttpRequest.newBuilder(URI("$baseUrl/lib/ajax/service.php?sesskey=$key&info=$method"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
        val root = runCatching { mapper.readTree(response.body()) }.getOrNull()
            ?: return AjaxResult(null, "invalidresponse", "HTTP ${response.statusCode()}")
        // Moodle answers with an array of results, or a single error object for session problems.
        val result = if (root.isArray) root.path(0) else root
        if (result.path("error").asBoolean(false) || result.has("errorcode")) {
            val exception = result.path("exception").takeIf { !it.isMissingNode } ?: result
            return AjaxResult(null, exception.path("errorcode").asString(null), exception.path("message").asString(null))
        }
        return AjaxResult(result.path("data"), null, null)
    }

    private suspend fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.sendAsync(
            request.timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).await()

    private fun formEncode(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }

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
