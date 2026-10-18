package com.syeyoung.assistant

import com.syeyoung.assistant.quickadd.LlmAt
import com.syeyoung.assistant.quickadd.LlmBy
import com.syeyoung.assistant.quickadd.LlmDraft
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CalendarSyncQuickAddTest : ApiTestBase() {

    @Test
    fun `calendar shows blocks, deadlines and all-day items`() {
        val lab = tag("lab", "#22C55E")
        val course = tag("CSED123", "#3B82F6")

        val assignment = post("/api/v1/items", mapOf("title" to "DB Assignment 3", "mainTagId" to course, "by" to mapOf("date" to "2026-10-23", "time" to "23:59")))
        post("/api/v1/items", mapOf("parentId" to assignment["id"], "at" to mapOf("start" to "2026-10-19T14:00:00+09:00", "end" to "2026-10-19T16:00:00+09:00")))
        post("/api/v1/items", mapOf("title" to "Meeting", "mainTagId" to lab, "at" to mapOf("start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00")))
        post("/api/v1/items", mapOf("title" to "Festival", "at" to mapOf("allDay" to true, "startDate" to "2026-10-20", "endDate" to "2026-10-21")))
        post("/api/v1/items", mapOf("title" to "Pay rent", "by" to mapOf("date" to "2026-10-24")))
        post("/api/v1/items", mapOf("title" to "Next month", "by" to mapOf("date" to "2026-11-20")))

        val week = getList("/api/v1/calendar?from=2026-10-18&to=2026-10-25")
        assertThat(week.map { it["kind"] to it["title"] }).containsExactly(
            "block" to "DB Assignment 3",
            "allDayBlock" to "Festival",
            "block" to "Meeting",
            "deadline" to "DB Assignment 3",
            "allDayDeadline" to "Pay rent",
        )
        val session = week.first()
        assertThat(session["session"]).isEqualTo(true)
        assertThat(session["color"]).isEqualTo("#3B82F6")
        assertThat(session["start"]).isEqualTo("2026-10-19T14:00:00+09:00")

        // Filtering by a tag includes sessions whose parent has it.
        val courseOnly = getList("/api/v1/calendar?from=2026-10-18&to=2026-10-25&tags=$course")
        assertThat(courseOnly.map { it["kind"] }).containsExactly("block", "deadline")

        val noSessions = getList("/api/v1/calendar?from=2026-10-18&to=2026-10-25&sessions=false")
        assertThat(noSessions.none { it["session"] == true }).isTrue()
    }

    @Test
    fun `change feed returns writes and deletes after a cursor`() {
        val start = (get("/api/v1/changes?since=0")["cursor"] as Number).toLong()
        val a = post("/api/v1/items", mapOf("title" to "A"))
        val t = tag("lab", "#22C55E")

        val page = get("/api/v1/changes?since=$start")
        assertThat((page["items"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly(a["id"])
        assertThat((page["tags"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly(t)
        val cursor = (page["cursor"] as Number).toLong()

        client.delete().uri("/api/v1/items/${a["id"]}").exchange().expectStatus().isNoContent
        val afterDelete = get("/api/v1/changes?since=$cursor")
        val deleted = (afterDelete["items"] as List<*>).single() as Map<*, *>
        assertThat(deleted["deletedAt"]).isNotNull()
        assertThat(afterDelete["hasMore"]).isEqualTo(false)
    }

    @Test
    fun `quick add maps the model's draft onto existing tags`() {
        val course = tag("CSED123", "#3B82F6")
        extractor.next = {
            LlmDraft(
                title = "DB assignment",
                by = LlmBy("2026-10-23", "23:00"),
                at = LlmAt(allDay = false, start = "2026-10-19T14:00"),
                tags = listOf("csed123", "newtag"),
            )
        }

        val result = post2xx("/api/v1/quick-add/parse", mapOf("text" to "DB hw due fri 11pm, work mon 2pm #csed123"))
        assertThat(result["parsed"]).isEqualTo(true)
        @Suppress("UNCHECKED_CAST")
        val draft = result["draft"] as Map<String, Any?>
        assertThat(draft["mainTagId"]).isEqualTo(course)
        assertThat(draft["at"]).isEqualTo(mapOf("allDay" to false, "start" to "2026-10-19T14:00:00+09:00", "end" to "2026-10-19T15:00:00+09:00"))
        assertThat(result["unknownTags"]).isEqualTo(listOf("newtag"))
        assertThat(extractor.lastInstructions).contains("Sunday 2026-10-18").contains("CSED123")
    }

    @Test
    fun `quick add falls back to a plain draft when the model fails`() {
        extractor.next = { error("boom") }
        val result = post2xx("/api/v1/quick-add/parse", mapOf("text" to "call mom"))
        assertThat(result["parsed"]).isEqualTo(false)
        assertThat((result["draft"] as Map<*, *>)["title"]).isEqualTo("call mom")
    }

    @Suppress("UNCHECKED_CAST")
    private fun post2xx(path: String, body: Any): Map<String, Any?> =
        client.post().uri(path).bodyValue(body).exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>
}
