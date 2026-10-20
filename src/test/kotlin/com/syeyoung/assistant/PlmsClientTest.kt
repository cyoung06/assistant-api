package com.syeyoung.assistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.syeyoung.assistant.source.plms.PlmsClient
import com.syeyoung.assistant.source.plms.PlmsException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Instant

/**
 * [PlmsClient] against a Moodle that logs in like PLMS: the login form sets a session cookie, the
 * login replaces it and redirects twice (through testsession) to the dashboard, which carries the
 * session key and web service token; the AJAX service wants the cookie and the key.
 */
class PlmsClientTest {
    private var logins = 0
    private val moodle = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val cookie = ex.requestHeaders.getFirst("Cookie").orEmpty()
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery.orEmpty()
            when {
                path == "/login/index.php" && ex.requestMethod == "GET" && query.isEmpty() -> {
                    ex.responseHeaders.add("Set-Cookie", "MoodleSession=before; Path=/; HttpOnly")
                    respond(ex, 200, """<form><input type="hidden" name="logintoken" value="LT1"></form>""")
                }
                path == "/login/index.php" && ex.requestMethod == "POST" -> {
                    logins++
                    val form = ex.requestBody.readAllBytes().decodeToString()
                    if ("MoodleSession=before" in cookie && "logintoken=LT1" in form && "password=right" in form) {
                        ex.responseHeaders.add("Set-Cookie", "MoodleSession=after; Path=/; HttpOnly")
                        redirect(ex, "/login/index.php?testsession=7")
                    } else respond(ex, 200, "<p>Invalid login</p>")
                }
                path == "/login/index.php" && query == "testsession=7" && "MoodleSession=after" in cookie -> redirect(ex, "/my/")
                path == "/my/" && "MoodleSession=after" in cookie -> respond(ex, 200, """M.cfg = {"sesskey":"SK1"}; MOODLE_WSTOKEN = "abc123";""")
                path == "/lib/ajax/service.php" && "sesskey=SK1" in query && "MoodleSession=after" in cookie ->
                    respond(ex, 200, """[{"error":false,"data":{"answer":42}}]""")
                path == "/lib/ajax/service.php" -> respond(ex, 200, """{"error":true,"errorcode":"servicerequireslogin"}""")
                path == "/webservice/pluginfile.php/5/mod_resource/content/1/notes.pdf" && query == "token=abc123" -> respond(ex, 200, "%PDF-fake")
                else -> respond(ex, 404, "")
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${moodle.address.port}"

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val b = body.toByteArray()
        ex.sendResponseHeaders(status, if (b.isEmpty()) -1 else b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.add("Location", to)
        ex.sendResponseHeaders(303, -1)
        ex.close()
    }

    /** Failures kept in memory, as the database would. */
    private class Failures : PlmsClient.LoginFailures {
        var last: Pair<Instant, String>? = null
        override suspend fun last() = last
        override suspend fun failed(at: Instant, credentials: String) { last = at to credentials }
        override suspend fun cleared() { last = null }
    }

    private fun client(password: String, failures: PlmsClient.LoginFailures = Failures()) = PlmsClient(
        base, "student", password, JsonMapper.builder().build(), failures,
        session = WebClient.builder().clientConnector(ClientHttpConnectorBuilder.detect().build(HttpClientSettings.defaults().withRedirects(HttpRedirects.DONT_FOLLOW))).build(),
        files = WebClient.create(),
    )

    @AfterEach
    fun stop() = moodle.stop(0)

    @Test
    fun `logs in through the redirects with the session's cookies, then calls the service`() = runBlocking {
        val plms = client("right")
        assertEquals(42, plms.call("core_test", emptyMap()).path("answer").asInt())
        // The session is kept: no second login.
        plms.call("core_test", emptyMap())
        assertEquals(1, logins)
    }

    @Test
    fun `a failed login isn't retried with the same password until the wait is over`() = runBlocking {
        val failures = Failures()
        assertThrows<PlmsException> { runBlocking { client("wrong", failures).call("core_test", emptyMap()) } }
        assertNotNull(failures.last)
        // A new client (a restart) with the same password doesn't try; a new password does.
        assertThrows<PlmsException> { runBlocking { client("wrong", failures).call("core_test", emptyMap()) } }
        assertEquals(1, logins)
        assertEquals(42, client("right", failures).call("core_test", emptyMap()).path("answer").asInt())
        assertEquals(2, logins)
    }

    @Test
    fun `course files stream with the token, and only from PLMS`(): Unit = runBlocking {
        val plms = client("right")
        val r = plms.download("$base/pluginfile.php/5/mod_resource/content/1/notes.pdf")
        val bytes = DataBufferUtils.join(r.body!!).map { b -> ByteArray(b.readableByteCount()).also { b.read(it); DataBufferUtils.release(b) } }.awaitSingle()
        assertEquals("%PDF-fake", String(bytes))
        assertThrows<PlmsException> { runBlocking { plms.download("https://evil.example/pluginfile.php/5/x.pdf") } }
    }
}
