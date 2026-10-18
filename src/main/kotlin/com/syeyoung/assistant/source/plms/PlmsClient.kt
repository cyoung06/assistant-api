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

    /** A PLMS page's HTML, as the logged-in user sees it. */
    suspend fun page(url: String): String {
        if (sesskey == null) login()
        val first = send(HttpRequest.newBuilder(URI(url)).GET())
        if (!first.uri().path.startsWith("/login/")) return first.body()
        login()
        return send(HttpRequest.newBuilder(URI(url)).GET()).body()
    }

    private suspend fun login(): String = loginLock.withLock {
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
        if (response.uri().path.startsWith("/login/")) throw PlmsException("PLMS login failed: check the username and password")
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

    private companion object {
        const val PAGE = 50
        const val USER_AGENT = "assistant-sync/1.0"
        val LOGIN_TOKEN = Regex("""name="logintoken" value="([^"]+)"""")
        val SESSKEY = Regex(""""sesskey":"([^"]+)"""")
        val SESSION_ERRORS = setOf("servicerequireslogin", "invalidsesskey", "requireloginerror", "sitepolicynotagreed")
    }
}
