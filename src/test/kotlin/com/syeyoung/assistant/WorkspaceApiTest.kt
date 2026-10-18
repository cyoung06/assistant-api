package com.syeyoung.assistant

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class WorkspaceApiTest : ApiTestBase() {

    private fun tasks(query: String = ""): List<Map<String, Any?>> = getList("/api/v1/tasks$query")

    private fun raw(taskId: String): String = runBlocking { dav.get(ws.findTask(taskId)!!.href)!!.data }

    @Test
    fun `tasks live in the assistant calendar with due, done, categories and sub-tasks`() {
        val hw = post("/api/v1/tasks", mapOf("title" to "HW6", "due" to mapOf("date" to "2026-10-20", "time" to "19:00"), "categories" to listOf("확률및통계", "assignment")))
        val id = hw["id"] as String
        assertThat(hw["calendarName"]).isEqualTo("Assistant")
        assertThat(hw["fixed"]).isEqualTo(false)
        assertThat(raw(id)).contains("BEGIN:VTODO", "SUMMARY:HW6", "DUE:20261020T100000Z", "CATEGORIES:확률및통계,assignment", "STATUS:NEEDS-ACTION")

        val sub = post("/api/v1/tasks", mapOf("title" to "Q1-3", "parentId" to id))
        assertThat(raw(sub["id"] as String)).contains("RELATED-TO;RELTYPE=PARENT:$id")
        assertThat(get("/api/v1/tasks/$id")["subtasks"]).isEqualTo(1)

        patch("/api/v1/tasks/$id", mapOf("done" to true))
        assertThat(raw(id)).contains("STATUS:COMPLETED", "PERCENT-COMPLETE:100")
        assertThat(get("/api/v1/tasks/${sub["id"]}")["done"]).isEqualTo(true)
        patch("/api/v1/tasks/$id", mapOf("done" to false))
        assertThat(raw(id)).contains("STATUS:NEEDS-ACTION").doesNotContain("COMPLETED:")
    }

    @Test
    fun `edits keep what other clients wrote`() {
        putRaw(
            "from-phone.ics",
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Tasks.org//EN
            BEGIN:VTODO
            UID:from-phone
            SUMMARY:Reply to professor
            X-APPLE-SORT-ORDER:12345
            CATEGORIES:reply,lab
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:reminder
            END:VALARM
            END:VTODO
            END:VCALENDAR
            """,
        )
        assertThat(tasks().single()["categories"]).isEqualTo(listOf("reply", "lab"))
        patch("/api/v1/tasks/from-phone", mapOf("done" to true, "title" to "Replied to professor"))
        assertThat(raw("from-phone")).contains("SUMMARY:Replied to professor", "X-APPLE-SORT-ORDER:12345", "BEGIN:VALARM", "TRIGGER:-PT15M", "STATUS:COMPLETED")
    }

    @Test
    fun `filters, overdue and hiding by category`() {
        post("/api/v1/tasks", mapOf("title" to "late", "due" to mapOf("date" to "2026-10-17")))
        post("/api/v1/tasks", mapOf("title" to "lecture notes", "categories" to listOf("class")))
        post("/api/v1/tasks", mapOf("title" to "later", "due" to mapOf("date" to "2026-11-01"), "categories" to listOf("lab")))
        assertThat(tasks().single { it["title"] == "late" }["overdue"]).isEqualTo(true)
        assertThat(tasks("?hide=class").map { it["title"] }).containsExactlyInAnyOrder("late", "later")
        assertThat(tasks("?category=lab").map { it["title"] }).containsExactly("later")
        // Calendars come from sources; they can't be created by hand.
        client.post().uri("/api/v1/calendars").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("name" to "x")).exchange().expectStatus().is4xxClientError
    }

    @Test
    fun `agenda expands repeating events from other apps, and one-off events can be moved`() {
        putRaw(
            "class.ics",
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//macOS//EN
            BEGIN:VTIMEZONE
            TZID:Asia/Seoul
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0900
            TZOFFSETTO:+0900
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:class
            DTSTART;TZID=Asia/Seoul:20260908T123000
            DTEND;TZID=Asia/Seoul:20260908T134500
            RRULE:FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261225T145959Z
            EXDATE;TZID=Asia/Seoul:20261022T123000
            SUMMARY:확률및통계
            CATEGORIES:확률및통계,class
            END:VEVENT
            END:VCALENDAR
            """,
        )
        post("/api/v1/tasks", mapOf("title" to "HW6", "due" to mapOf("date" to "2026-10-20", "time" to "19:00")))
        val week = getList("/api/v1/agenda?from=2026-10-18&to=2026-10-24")
        assertThat(week.map { it["kind"] to it["title"] }).containsExactly("block" to "확률및통계", "deadline" to "HW6")
        assertThat(week.first()["start"]).isEqualTo("2026-10-20T12:30:00+09:00")
        assertThat(week.first()["fixed"]).isEqualTo(true)
        assertThat(getList("/api/v1/agenda?from=2026-10-18&to=2026-10-24&hide=class").map { it["title"] }).containsExactly("HW6")

        // Repeating events are changed in a calendar app; one-off events move here.
        client.patch().uri("/api/v1/events/class").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("start" to "2026-10-20T13:00:00+09:00", "end" to "2026-10-20T14:00:00+09:00")).exchange().expectStatus().isEqualTo(409)
        val meeting = client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Meeting", "start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00"))
            .exchange().expectStatus().isCreated.expectBody(Map::class.java).returnResult().responseBody!!
        val moved = client.patch().uri("/api/v1/events/${meeting["id"]}").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("start" to "2026-10-23T10:00:00+09:00", "end" to "2026-10-23T11:30:00+09:00"))
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!
        assertThat(moved["start"]).isEqualTo("2026-10-23T10:00:00+09:00")
        assertThat(moved["fixed"]).isEqualTo(false)
    }

    @Test
    fun `work sessions count toward time spent`() {
        val id = post("/api/v1/tasks", mapOf("title" to "DB Assignment 3", "due" to mapOf("date" to "2026-10-23")))["id"] as String
        for ((s, e) in listOf("2026-10-17T14:00:00+09:00" to "2026-10-17T16:00:00+09:00", "2026-10-19T10:00:00+09:00" to "2026-10-19T13:00:00+09:00")) {
            client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("taskId" to id, "title" to "DB Assignment 3", "start" to s, "end" to e)).exchange().expectStatus().isCreated
        }
        val after = get("/api/v1/tasks/$id")
        assertThat(after["minutesSpent"]).isEqualTo(120)
        assertThat(after["minutesPlanned"]).isEqualTo(180)
        client.delete().uri("/api/v1/tasks/$id").exchange().expectStatus().isNoContent
        assertThat(getList("/api/v1/agenda?from=2026-10-17&to=2026-10-19")).isEmpty()
    }
}
