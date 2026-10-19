package com.syeyoung.assistant

import com.syeyoung.assistant.capture.CaptureImage
import com.syeyoung.assistant.capture.KoogCaptureModel
import com.syeyoung.assistant.llm.ChatGptAccount
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper
import java.net.URI

class ChatGptTest : ApiTestBase() {
    @Autowired lateinit var model: KoogCaptureModel
    @Autowired lateinit var account: ChatGptAccount
    @Autowired lateinit var mapper: ObjectMapper

    @BeforeEach
    fun reset() {
        TestOpenAI.reset()
        account.forgetAccessToken()
    }

    /** Signs in: makes the link, then pastes back the localhost address OpenAI would send the browser to. */
    private fun signIn() {
        val link = post200("/api/v1/chatgpt/connect", emptyMap<String, Any>())["authorizeUrl"] as String
        val state = URI(link).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
        val status = post200("/api/v1/chatgpt/complete", mapOf("redirectUrl" to "http://localhost:1455/auth/callback?code=good&scope=openid&state=$state"))
        assertThat(status).containsEntry("state", "connected").containsEntry("email", TestOpenAI.EMAIL).containsEntry("plan", "plus")
    }

    @Suppress("UNCHECKED_CAST")
    private fun post200(path: String, body: Any): Map<String, Any?> =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Test
    fun `signs in with Codex's client and PKCE`() {
        assertThat(get("/api/v1/chatgpt/account")).containsEntry("state", "disconnected").containsEntry("model", "gpt-6-luna")
        val link = URI(post200("/api/v1/chatgpt/connect", emptyMap<String, Any>())["authorizeUrl"] as String)
        assertThat(link.path).isEqualTo("/oauth/authorize")
        assertThat(link.query).contains("client_id=app_EMoamEEZ73f0CkXaXp7hrann", "redirect_uri=http://localhost:1455/auth/callback", "code_challenge_method=S256")
        assertThat(get("/api/v1/chatgpt/account")).containsEntry("state", "pending")

        // An address from another sign-in, or a bad code, is refused.
        client.post().uri("/api/v1/chatgpt/complete").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("redirectUrl" to "http://localhost:1455/auth/callback?code=good&state=other")).exchange().expectStatus().isBadRequest
        val state = link.rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
        client.post().uri("/api/v1/chatgpt/complete").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("redirectUrl" to "http://localhost:1455/auth/callback?code=bad&state=$state")).exchange().expectStatus().isEqualTo(409)

        post200("/api/v1/chatgpt/complete", mapOf("redirectUrl" to "http://localhost:1455/auth/callback?code=good&state=$state"))
        val challenge = link.rawQuery.split('&').first { it.startsWith("code_challenge=") }.substringAfter('=')
        assertThat(com.syeyoung.assistant.llm.ChatGptOAuth.challenge(TestOpenAI.lastForm["code_verifier"]!!)).isEqualTo(challenge)
        assertThat(get("/api/v1/chatgpt/account")).containsEntry("state", "connected").containsEntry("email", TestOpenAI.EMAIL)

        delete("/api/v1/chatgpt/account")
        assertThat(get("/api/v1/chatgpt/account")).containsEntry("state", "disconnected")
    }

    @Test
    fun `reads through the Codex backend, streaming, with the account's token`() {
        runBlocking { assertThat(model.available()).isFalse() }
        signIn()
        TestOpenAI.reply = """{"items":[{"kind":"task","title":"HW7","description":"","dueDate":"2026-10-27","dueTime":"23:59","start":null,"end":null,"allDayDate":null,"project":null,"labels":[],"priority":null}]}"""
        val result = runBlocking {
            assertThat(model.available()).isTrue()
            model.read("Find tasks.", "HW7 due Oct 27", listOf(CaptureImage(byteArrayOf(1, 2, 3), "image/png", "shot.png")))
        }
        assertThat(result.items.single().title).isEqualTo("HW7")
        assertThat(result.items.single().dueDate).isEqualTo("2026-10-27")

        assertThat(TestOpenAI.lastHeaders["authorization"]).startsWith("Bearer ey")
        assertThat(TestOpenAI.lastHeaders["chatgpt-account-id"]).isEqualTo(TestOpenAI.ACCOUNT)
        val body = mapper.readTree(TestOpenAI.lastBody)
        assertThat(body.path("model").asString()).isEqualTo("gpt-6-luna")
        assertThat(body.path("instructions").asString()).isEqualTo("Find tasks.")
        assertThat(body.path("stream").asBoolean()).isTrue()
        assertThat(body.path("store").asBoolean(true)).isFalse()
        assertThat(body.path("text").path("format").path("type").asString()).isEqualTo("json_schema")
        assertThat(TestOpenAI.lastBody).contains("data:image/png;base64,AQID")

        @Suppress("UNCHECKED_CAST")
        val quota = get("/api/v1/chatgpt/account")["quota"] as List<Map<String, Any?>>
        assertThat(quota.map { it["name"] to it["usedPercent"] }).containsExactly("5-hour" to 23.5, "Weekly" to 40.0)
    }

    @Test
    fun `refreshes an expiring token, and a rejected refresh asks to reconnect`() {
        TestOpenAI.accessExpires = 1_000_000_000 // long past
        signIn()
        TestOpenAI.reply = """{"items":[]}"""
        runBlocking { model.read("x", "y", emptyList()) }
        assertThat(TestOpenAI.refreshes.get()).isEqualTo(1)
        assertThat(get("/api/v1/chatgpt/account")).containsEntry("state", "connected")

        TestOpenAI.refreshFails = true
        account.forgetAccessToken()
        assertThatThrownBy { runBlocking { model.read("x", "y", emptyList()) } }.hasMessageContaining("refresh token")
        assertThat(get("/api/v1/chatgpt/account")).containsEntry("state", "error")
    }
}
