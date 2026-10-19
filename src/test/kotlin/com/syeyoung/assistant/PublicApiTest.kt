package com.syeyoung.assistant

import com.syeyoung.assistant.capture.LlmCapture
import com.syeyoung.assistant.capture.LlmItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType

class PublicApiTest : ApiTestBase() {
    @Autowired lateinit var model: FakeCaptureModel

    private val publicHost = "assistant.public.test"

    private fun token() = post("/api/v1/public-tokens", mapOf("name" to "Outlook"))["token"] as String

    @Test
    fun `the public host only serves the public endpoints`() {
        client.get().uri("/api/v1/tasks").header("Host", publicHost).exchange().expectStatus().isNotFound
        client.get().uri("/api/v1/public-tokens").header("X-Forwarded-Host", publicHost).exchange().expectStatus().isNotFound
        client.get().uri("/api/v1/public/projects").header("Host", publicHost).exchange().expectStatus().isUnauthorized
        // On the tailnet everything stays reachable.
        client.get().uri("/api/v1/tasks").exchange().expectStatus().isOk
    }

    @Test
    fun `a token creates tasks with labels and can be revoked`() {
        val course = post("/api/v1/projects", mapOf("name" to "Lab"))
        val token = token()
        assertThat(token).startsWith("ast_")
        val projects = client.get().uri("/api/v1/public/projects").header("Host", publicHost).header("Authorization", "Bearer $token")
            .exchange().expectStatus().isOk.expectBodyList(Map::class.java).returnResult().responseBody!!
        assertThat(projects.map { it["name"] }).containsExactly("Lab")

        client.post().uri("/api/v1/public/tasks").header("Host", publicHost).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Reply to prof", "description" to "[Open in Outlook](https://outlook.office.com/mail/deeplink/read/x)", "labels" to listOf("reply"), "projectId" to course["id"]))
            .exchange().expectStatus().isCreated
        val task = getList("/api/v1/tasks").single()
        assertThat(task["title"]).isEqualTo("Reply to prof")
        assertThat(task["projectId"]).isEqualTo(course["id"])
        assertThat(getList("/api/v1/labels").single()["name"]).isEqualTo("reply")
        assertThat(getList("/api/v1/public-tokens").single()["lastUsed"]).isNotNull()

        // The open mail goes to Triage; only a mid: link is kept as its source.
        client.post().uri("/api/v1/public/triage").header("Host", publicHost).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Lab schedule", "text" to "Meeting moved to Thu 3pm", "link" to "mid:lab@postech.ac.kr"))
            .exchange().expectStatus().isAccepted
        assertThat(getList("/api/v1/triage").single()).containsEntry("source", "addin").containsEntry("title", "Lab schedule").containsEntry("link", "mid:lab@postech.ac.kr")

        val id = getList("/api/v1/public-tokens").single()["id"]
        delete("/api/v1/public-tokens/$id")
        client.get().uri("/api/v1/public/projects").header("Authorization", "Bearer $token").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `analyze reads text only, and requests are rate limited`() {
        val token = token()
        model.configured = true
        model.next = LlmCapture(listOf(LlmItem("task", "Send the report", dueDate = "2026-10-23")))
        val items = client.post().uri("/api/v1/public/analyze").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("text" to "Please send the report by Friday", "attachmentIds" to listOf("00000000-0000-0000-0000-000000000000")))
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!["items"] as List<*>
        assertThat((items.single() as Map<*, *>)["title"]).isEqualTo("Send the report")
        assertThat(model.lastImages).isEmpty()

        repeat(60) { client.get().uri("/api/v1/public/projects").header("Authorization", "Bearer $token").exchange() }
        client.get().uri("/api/v1/public/projects").header("Authorization", "Bearer $token").exchange().expectStatus().isEqualTo(429)
    }
}
