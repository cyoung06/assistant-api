package com.syeyoung.assistant

import com.syeyoung.assistant.quickadd.LlmAt
import com.syeyoung.assistant.quickadd.LlmBy
import com.syeyoung.assistant.quickadd.LlmDraft
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuickAddTest : ApiTestBase() {
    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String) =
        client.post().uri("/api/v1/quick-add/parse").bodyValue(mapOf("text" to text)).exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Test
    fun `deadlines become tasks, timed things become events, and calendar names are matched`() {
        val stats = calendar("확률및통계")
        extractor.next = { LlmDraft(title = "HW7", by = LlmBy("2026-10-27", "19:00"), tags = listOf("확률및통계", "assignment")) }
        val task = parse("확통 HW7 화요일 7시까지")["draft"] as Map<*, *>
        assertThat(task["kind"]).isEqualTo("task")
        assertThat(task["calendarId"]).isEqualTo(stats)
        assertThat(task["categories"]).isEqualTo(listOf("assignment"))
        assertThat(task["due"]).isEqualTo(mapOf("date" to "2026-10-27", "time" to "19:00:00"))

        extractor.next = { LlmDraft(title = "교수님 미팅", at = LlmAt(allDay = false, start = "2026-10-22T15:00")) }
        val event = parse("목요일 3시 교수님 미팅")["draft"] as Map<*, *>
        assertThat(event["kind"]).isEqualTo("event")
        assertThat(event["start"]).isEqualTo("2026-10-22T15:00:00+09:00")
        assertThat(event["end"]).isEqualTo("2026-10-22T16:00:00+09:00")
        assertThat(extractor.lastInstructions).contains("Sunday 2026-10-18", "확률및통계")
    }

    @Test
    fun `falls back to a plain task when the model fails`() {
        extractor.next = { error("boom") }
        val result = parse("call mom")
        assertThat(result["parsed"]).isEqualTo(false)
        assertThat((result["draft"] as Map<*, *>)["title"]).isEqualTo("call mom")
    }
}
