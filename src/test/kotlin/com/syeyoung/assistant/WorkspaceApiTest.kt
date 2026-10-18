package com.syeyoung.assistant

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class WorkspaceApiTest : ApiTestBase() {

    @Suppress("UNCHECKED_CAST")
    private fun tasks(query: String = ""): List<Map<String, Any?>> = getList("/api/v1/tasks$query")

    private fun raw(taskId: String): String = runBlocking {
        val href = ws.findTask(taskId)!!.href
        dav.get(href)!!.data
    }

    @Test
    fun `tasks live in CalDAV with due, done, categories and sub-tasks`() {
        val stats = calendar("확률및통계")
        val hw = post("/api/v1/tasks", mapOf("calendarId" to stats, "title" to "HW6", "due" to mapOf("date" to "2026-10-20", "time" to "19:00"), "categories" to listOf("assignment")))
        val id = hw["id"] as String
        assertThat(hw["calendarName"]).isEqualTo("확률및통계")
        assertThat(hw["color"]).isEqualTo("#2F5BE0")
        assertThat(raw(id)).contains("BEGIN:VTODO", "SUMMARY:HW6", "DUE:20261020T100000Z", "CATEGORIES:assignment", "STATUS:NEEDS-ACTION")

        val sub = post("/api/v1/tasks", mapOf("title" to "Q1-3", "parentId" to id))
        assertThat(sub["calendarId"]).isEqualTo(stats)
        assertThat(raw(sub["id"] as String)).contains("RELATED-TO;RELTYPE=PARENT:$id")
        assertThat(get("/api/v1/tasks/$id")["subtasks"]).isEqualTo(1)

        // Done is STATUS:COMPLETED for every client, and completes sub-tasks.
        patch("/api/v1/tasks/$id", mapOf("done" to true))
        assertThat(raw(id)).contains("STATUS:COMPLETED", "COMPLETED:2026", "PERCENT-COMPLETE:100")
        assertThat(get("/api/v1/tasks/${sub["id"]}")["done"]).isEqualTo(true)
        patch("/api/v1/tasks/$id", mapOf("done" to false))
        assertThat(raw(id)).contains("STATUS:NEEDS-ACTION").doesNotContain("COMPLETED:")
    }

    @Test
    fun `edits keep what other clients wrote`() {
        val cal = calendar("Lab")
        runBlocking {
            val href = dav.collections().single().href + "from-phone.ics"
            dav.put(
                href,
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
                """.trimIndent().replace("\n", "\r\n") + "\r\n",
                null,
            )
            ws.invalidate()
        }
        val t = tasks().single()
        assertThat(t["calendarId"]).isEqualTo(cal)
        assertThat(t["categories"]).isEqualTo(listOf("reply", "lab"))
        patch("/api/v1/tasks/from-phone", mapOf("done" to true, "title" to "Replied to professor"))
        val after = raw("from-phone")
        assertThat(after).contains("SUMMARY:Replied to professor", "X-APPLE-SORT-ORDER:12345", "BEGIN:VALARM", "TRIGGER:-PT15M", "STATUS:COMPLETED")
    }

    @Test
    fun `filters, overdue, hiding by calendar or category, and moving between calendars`() {
        val a = calendar("A")
        val b = calendar("B", "#E0642F")
        post("/api/v1/tasks", mapOf("calendarId" to a, "title" to "late", "due" to mapOf("date" to "2026-10-17")))
        post("/api/v1/tasks", mapOf("calendarId" to a, "title" to "lecture notes", "categories" to listOf("class")))
        val t = post("/api/v1/tasks", mapOf("calendarId" to b, "title" to "later", "due" to mapOf("date" to "2026-11-01")))

        assertThat(tasks().single { it["title"] == "late" }["overdue"]).isEqualTo(true)
        assertThat(tasks("?hide=class").map { it["title"] }).containsExactlyInAnyOrder("late", "later")
        assertThat(tasks("?hide=B").map { it["title"] }).containsExactlyInAnyOrder("late", "lecture notes")
        assertThat(tasks("?calendarId=$b").map { it["title"] }).containsExactly("later")

        patch("/api/v1/tasks/${t["id"]}", mapOf("calendarId" to a))
        assertThat(tasks("?calendarId=$a")).hasSize(3)
        // A non-empty calendar can't be deleted.
        client.delete().uri("/api/v1/calendars/$a").exchange().expectStatus().isEqualTo(409)
        client.delete().uri("/api/v1/calendars/$b").exchange().expectStatus().isNoContent
    }

    @Test
    fun `agenda expands repeating events written by other apps, skips EXDATEs, and lists deadlines`() {
        calendar("확률및통계")
        runBlocking {
            dav.put(
                dav.collections().single().href + "class.ics",
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
                CATEGORIES:class
                END:VEVENT
                END:VCALENDAR
                """.trimIndent().replace("\n", "\r\n") + "\r\n",
                null,
            )
            ws.invalidate()
        }
        post("/api/v1/tasks", mapOf("title" to "HW6", "due" to mapOf("date" to "2026-10-20", "time" to "19:00")))
        val week = getList("/api/v1/agenda?from=2026-10-18&to=2026-10-24")
        assertThat(week.map { it["kind"] to it["title"] }).containsExactly("block" to "확률및통계", "deadline" to "HW6")
        assertThat(week.first()["start"]).isEqualTo("2026-10-20T12:30:00+09:00")
        assertThat(week.first()["repeats"]).isEqualTo(true)
        assertThat(getList("/api/v1/agenda?from=2026-10-18&to=2026-10-24&hide=class").map { it["title"] }).containsExactly("HW6")
    }

    @Test
    fun `work sessions count toward time spent`() {
        val t = post("/api/v1/tasks", mapOf("title" to "DB Assignment 3", "due" to mapOf("date" to "2026-10-23")))
        val id = t["id"] as String
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("taskId" to id, "title" to "DB Assignment 3", "start" to "2026-10-17T14:00:00+09:00", "end" to "2026-10-17T16:00:00+09:00"))
            .exchange().expectStatus().isCreated
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("taskId" to id, "title" to "DB Assignment 3", "start" to "2026-10-19T10:00:00+09:00", "end" to "2026-10-19T13:00:00+09:00"))
            .exchange().expectStatus().isCreated
        val after = get("/api/v1/tasks/$id")
        assertThat(after["minutesSpent"]).isEqualTo(120)
        assertThat(after["minutesPlanned"]).isEqualTo(180)

        client.delete().uri("/api/v1/tasks/$id").exchange().expectStatus().isNoContent
        assertThat(getList("/api/v1/agenda?from=2026-10-17&to=2026-10-19")).isEmpty()
    }
}
