package com.syeyoung.assistant

import com.syeyoung.assistant.capture.LlmCapture
import com.syeyoung.assistant.capture.LlmItem
import com.syeyoung.assistant.triage.TriageService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters

class TriageTest : ApiTestBase() {
    @Autowired lateinit var model: FakeCaptureModel
    @Autowired lateinit var triage: TriageService

    @BeforeEach
    fun reset() {
        model.configured = true
        model.failure = null
        model.next = LlmCapture()
    }

    @Suppress("UNCHECKED_CAST")
    private fun submit(body: Map<String, Any?>): Map<String, Any?> =
        client.post().uri("/api/v1/triage/inputs").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isAccepted
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun inputs() = getList("/api/v1/triage")

    @Suppress("UNCHECKED_CAST")
    private fun items(input: Map<String, Any?>) = input["items"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun upload(): Map<String, Any?> {
        val body = MultipartBodyBuilder().apply {
            part("file", object : ByteArrayResource(byteArrayOf(-119, 80, 78, 71)) { override fun getFilename() = "notice.png" }).contentType(MediaType.IMAGE_PNG)
        }.build()
        return client.post().uri("/api/v1/attachments").contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(body))
            .exchange().expectStatus().isCreated.expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>
    }

    private fun drain() = runBlocking { triage.drain() }

    @Test
    fun `shared screenshots are read in the background and suggestions become tasks only when accepted`() {
        val existing = post("/api/v1/tasks", mapOf("title" to "HW7"))
        val shot = upload()
        val input = submit(mapOf("source" to "share", "text" to "from the syllabus", "attachmentIds" to listOf(shot["id"]), "link" to "mid:notice@postech.ac.kr", "title" to "Syllabus"))
        assertThat(input).containsEntry("status", "pending").containsEntry("title", "Syllabus")

        model.next = LlmCapture(listOf(LlmItem("task", "HW7", "Problems 1–5", dueDate = "2026-10-27", sameAsTask = 1), LlmItem("task", "Read ch. 3")))
        drain()
        val read = inputs().single()
        assertThat(read["status"]).isEqualTo("done")
        assertThat(items(read).map { (it["proposal"] as Map<*, *>)["title"] to it["sameAs"] }).containsExactly("HW7" to 1, "Read ch. 3" to null)
        assertThat(model.lastImages.single().fileName).isEqualTo("notice.png")
        assertThat(model.lastInstructions).contains("#1 HW7")
        // Nothing is made until accepted.
        assertThat(getList("/api/v1/tasks").map { it["title"] }).containsExactly("HW7")

        val second = items(read)[1]
        val edited = (second["proposal"] as Map<*, *>) + ("title" to "Read chapter 3")
        val accepted = client.post().uri("/api/v1/triage/items/${second["id"]}/accept").contentType(MediaType.APPLICATION_JSON).bodyValue(edited)
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!
        assertThat(accepted["status"]).isEqualTo("accepted")
        val task = getList("/api/v1/tasks").single { it["title"] == "Read chapter 3" }
        assertThat(accepted["result"] as String).endsWith("/task/${task["id"]}")
        assertThat(task["description"]).isEqualTo("From: [Syllabus](mid:notice@postech.ac.kr)")
        assertThat((task["attachments"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly(shot["id"])

        client.post().uri("/api/v1/triage/items/${items(read)[0]["id"]}/decline").exchange().expectStatus().isOk
        assertThat(items(inputs().single()).map { it["status"] }).containsExactly("declined", "accepted")
        client.post().uri("/api/v1/triage/items/${items(read)[0]["id"]}/accept").exchange().expectStatus().isEqualTo(409)
        assertThat(getList("/api/v1/tasks")).hasSize(2)
        assertThat(existing["title"]).isEqualTo("HW7")
    }

    @Test
    fun `inputs wait while the model is away and are retried after failures`() {
        model.configured = false
        val input = submit(mapOf("text" to "Meeting with Prof. Kim Thursday 3pm"))
        assertThat(input["title"]).isEqualTo("Meeting with Prof. Kim Thursday 3pm")
        drain()
        assertThat(inputs().single()["status"]).isEqualTo("pending")

        // Back, but failing: counted, and retried later.
        model.configured = true
        model.failure = IllegalStateException("upstream 500")
        client.post().uri("/api/v1/triage/inputs/${input["id"]}/retry").exchange().expectStatus().isOk
        drain()
        assertThat(inputs().single()).containsEntry("status", "pending").containsEntry("error", "The model couldn't read this: upstream 500")

        model.failure = null
        model.next = LlmCapture(listOf(LlmItem("event", "Meeting with Prof. Kim", start = "2026-10-22T15:00", end = "2026-10-22T16:00")))
        client.post().uri("/api/v1/triage/inputs/${input["id"]}/retry").exchange().expectStatus().isOk
        drain()
        val item = items(inputs().single()).single()
        client.post().uri("/api/v1/triage/items/${item["id"]}/accept").exchange().expectStatus().isOk
        assertThat(getList("/api/v1/events?from=2026-10-22&to=2026-10-22").map { it["title"] }).containsExactly("Meeting with Prof. Kim")

        client.delete().uri("/api/v1/triage/inputs/${input["id"]}").exchange().expectStatus().isNoContent
        assertThat(inputs()).isEmpty()
        client.post().uri("/api/v1/triage/inputs").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("text" to " ")).exchange().expectStatus().isBadRequest
    }
}
