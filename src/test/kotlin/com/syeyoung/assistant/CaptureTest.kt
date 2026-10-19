package com.syeyoung.assistant

import com.syeyoung.assistant.capture.CaptureImage
import com.syeyoung.assistant.capture.CaptureModel
import com.syeyoung.assistant.capture.LlmCapture
import com.syeyoung.assistant.capture.LlmItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters

/** Stands in for the model: tests set what it "reads". */
class FakeCaptureModel : CaptureModel {
    var configured = true
    override suspend fun available() = configured
    var next = LlmCapture()
    var lastInstructions = ""
    var lastImages = emptyList<CaptureImage>()
    var failure: Exception? = null
    var lastText = ""
    var lastLight = false
    /** How long each read takes, and the most reads seen at once. */
    var delayMillis = 0L
    private val running = java.util.concurrent.atomic.AtomicInteger()
    val mostAtOnce = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun read(instructions: String, text: String, images: List<CaptureImage>, light: Boolean): LlmCapture {
        val now = running.incrementAndGet()
        mostAtOnce.accumulateAndGet(now, ::maxOf)
        try {
            kotlinx.coroutines.delay(delayMillis)
            return answer(instructions, text, images, light)
        } finally {
            running.decrementAndGet()
        }
    }

    private fun answer(instructions: String, text: String, images: List<CaptureImage>, light: Boolean): LlmCapture {
        lastInstructions = instructions
        lastImages = images
        lastText = text
        lastLight = light
        failure?.let { throw it }
        return next
    }
}

class CaptureTest : ApiTestBase() {
    @Autowired lateinit var model: FakeCaptureModel

    @BeforeEach
    fun reset() {
        model.configured = true
        model.failure = null
        model.next = LlmCapture()
    }

    @Suppress("UNCHECKED_CAST")
    private fun upload(name: String, type: MediaType, bytes: ByteArray): Map<String, Any?> {
        val body = MultipartBodyBuilder().apply {
            part("file", object : ByteArrayResource(bytes) { override fun getFilename() = name }).contentType(type)
        }.build()
        return client.post().uri("/api/v1/attachments").contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(body))
            .exchange().expectStatus().isCreated.expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>
    }

    @Test
    fun `screenshots are kept with tasks and served back`() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val a = upload("notice.png", MediaType.IMAGE_PNG, png)
        assertThat(a).containsEntry("filename", "notice.png").containsEntry("contentType", "image/png").containsEntry("size", 8)
        val t = post("/api/v1/tasks", mapOf("title" to "Read the notice", "attachmentIds" to listOf(a["id"])))
        assertThat((t["attachments"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly(a["id"])
        val served = client.get().uri("/api/v1/attachments/${a["id"]}").exchange().expectStatus().isOk
            .expectHeader().contentType(MediaType.IMAGE_PNG).expectBody(ByteArray::class.java).returnResult().responseBody
        assertThat(served).isEqualTo(png)
        assertThat(patch("/api/v1/tasks/${t["id"]}", mapOf("attachmentIds" to emptyList<String>()))["attachments"] as List<*>).isEmpty()
        client.get().uri("/api/v1/attachments/${a["id"]}").exchange().expectStatus().isNotFound
    }

    @Test
    fun `analyze turns what the model read into proposals, creating nothing`() {
        val course = post("/api/v1/projects", mapOf("name" to "확률및통계"))
        val label = post("/api/v1/labels", mapOf("name" to "exam"))
        val shot = upload("syllabus.png", MediaType.IMAGE_PNG, byteArrayOf(1, 2, 3))
        model.next = LlmCapture(
            listOf(
                LlmItem("task", "HW7", "Problems 1–5", dueDate = "2026-10-27", dueTime = "23:59", project = "확률및통계"),
                LlmItem("event", "Midterm", "Room 101", start = "2026-10-30T10:00", end = "2026-10-30T12:00", project = "확률및통계", labels = listOf("exam", "unknown")),
                LlmItem("event", "Festival", allDayDate = "2026-11-02"),
                LlmItem("event", "Office hours sometime"),
                LlmItem("task", "  "),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val items = client.post().uri("/api/v1/capture/analyze").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("text" to "from the syllabus", "attachmentIds" to listOf(shot["id"])))
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!["items"] as List<Map<String, Any?>>

        assertThat(items.map { it["kind"] to it["title"] }).containsExactly(
            "task" to "HW7", "event" to "Midterm", "event" to "Festival", "task" to "Office hours sometime",
        )
        assertThat(items[0]).containsEntry("due", mapOf("date" to "2026-10-27", "time" to "23:59:00")).containsEntry("projectId", course["id"])
        assertThat(items[1]).containsEntry("start", "2026-10-30T10:00:00+09:00").containsEntry("labelIds", listOf(label["id"]))
        assertThat(items[2]).containsEntry("startDate", "2026-11-02").containsEntry("endDate", "2026-11-02")
        assertThat(model.lastImages.single().fileName).isEqualTo("syllabus.png")
        assertThat(model.lastInstructions).contains("2026-10-18 (Sunday) 10:00", "\"확률및통계\"", "\"exam\"")
        assertThat(getList("/api/v1/tasks")).isEmpty()
    }

    @Test
    fun `analyze needs a model and something to read`() {
        client.post().uri("/api/v1/capture/analyze").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("text" to ""))
            .exchange().expectStatus().isBadRequest
        model.configured = false
        client.post().uri("/api/v1/capture/analyze").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("text" to "x"))
            .exchange().expectStatus().isEqualTo(409)
    }
}
