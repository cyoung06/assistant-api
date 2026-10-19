package com.syeyoung.assistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenAI for tests: the sign-in's token endpoint (code "good" with any PKCE verifier, and
 * refreshes) and the Codex backend's streaming Responses endpoint, which answers with [reply].
 */
object TestOpenAI {
    const val ACCOUNT = "acct-123"
    const val EMAIL = "me@example.com"

    @Volatile var reply = """{"items":[]}"""
    /** When the access tokens it hands out expire (epoch seconds). */
    @Volatile var accessExpires = 4_000_000_000L
    @Volatile var refreshFails = false
    @Volatile var lastForm: Map<String, String> = emptyMap()
    @Volatile var lastHeaders: Map<String, String> = emptyMap()
    @Volatile var lastBody = ""
    val refreshes = AtomicInteger()
    private val issued = AtomicInteger()

    val url: String by lazy {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oauth/token") { ex ->
            val form = ex.requestBody.readAllBytes().decodeToString().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
            lastForm = form
            when {
                form["grant_type"] == "authorization_code" && form["code"] == "good" && !form["code_verifier"].isNullOrBlank() -> respond(ex, 200, tokens())
                form["grant_type"] == "refresh_token" && refreshFails ->
                    respond(ex, 401, """{"error":{"message":"Your refresh token has already been used","code":"refresh_token_reused"}}""")
                form["grant_type"] == "refresh_token" -> { refreshes.incrementAndGet(); respond(ex, 200, tokens()) }
                else -> respond(ex, 400, """{"error":"invalid_grant","error_description":"Invalid authorization code"}""")
            }
        }
        server.createContext("/backend-api/codex/responses") { ex ->
            lastHeaders = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
            lastBody = ex.requestBody.readAllBytes().decodeToString()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("x-codex-primary-used-percent", "23.5")
            ex.responseHeaders.add("x-codex-primary-window-minutes", "300")
            ex.responseHeaders.add("x-codex-primary-reset-at", "1760800000")
            ex.responseHeaders.add("x-codex-secondary-used-percent", "40")
            ex.responseHeaders.add("x-codex-secondary-window-minutes", "10080")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write(stream(reply).toByteArray()) }
        }
        server.start()
        "http://127.0.0.1:${server.address.port}"
    }

    fun reset() {
        reply = """{"items":[]}"""
        accessExpires = 4_000_000_000L
        refreshFails = false
        refreshes.set(0)
        lastBody = ""
    }

    private fun tokens(): String {
        val n = issued.incrementAndGet()
        val id = jwt("""{"email":"$EMAIL","https://api.openai.com/auth":{"chatgpt_account_id":"$ACCOUNT","chatgpt_plan_type":"plus"}}""")
        val access = jwt("""{"exp":$accessExpires,"n":$n}""")
        return """{"id_token":"$id","access_token":"$access","refresh_token":"refresh-$n","expires_in":3600}"""
    }

    private fun jwt(claims: String): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return b64.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + b64.encodeToString(claims.toByteArray()) + ".sig"
    }

    /** A Responses API event stream that writes [text] as the assistant's message, in two pieces. */
    private fun stream(text: String): String {
        val quoted = { s: String -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        val half = text.length / 2
        val message = """{"id":"msg_1","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":${quoted(text)},"annotations":[]}]}"""
        val response = { status: String, output: String ->
            """{"id":"resp_1","object":"response","created_at":1760000000,"status":"$status","model":"gpt-6-luna","error":null,"incomplete_details":null,"instructions":null,"max_output_tokens":null,"metadata":{},"output":$output,"parallel_tool_calls":true,"previous_response_id":null,"reasoning":{"effort":"medium","summary":null},"store":false,"temperature":1.0,"text":{"format":{"type":"json_schema","name":"capture","schema":{},"strict":true}},"tool_choice":"auto","tools":[],"top_p":1.0,"truncation":"disabled","usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":0},"output_tokens":5,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":15}}"""
        }
        val events = listOf(
            "response.created" to """{"type":"response.created","sequence_number":0,"response":${response("in_progress", "[]")}}""",
            "response.output_item.added" to """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}""",
            "response.content_part.added" to """{"type":"response.content_part.added","sequence_number":2,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":3,"item_id":"msg_1","output_index":0,"content_index":0,"delta":${quoted(text.take(half))}}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":4,"item_id":"msg_1","output_index":0,"content_index":0,"delta":${quoted(text.drop(half))}}""",
            "response.output_text.done" to """{"type":"response.output_text.done","sequence_number":5,"item_id":"msg_1","output_index":0,"content_index":0,"text":${quoted(text)}}""",
            "response.output_item.done" to """{"type":"response.output_item.done","sequence_number":6,"output_index":0,"item":$message}""",
            "response.completed" to """{"type":"response.completed","sequence_number":7,"response":${response("completed", "[$message]")}}""",
        )
        return events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
