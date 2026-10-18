package com.syeyoung.assistant

import com.syeyoung.assistant.dav.Due
import com.syeyoung.assistant.dav.Workspace
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RestructureTest : ApiTestBase() {
    @Test
    fun `moves from a calendar per course to a calendar per source`() {
        runBlocking {
            val stats = ws.createCalendar("확률및통계", "#2F5BE0")
            val holiday = ws.createCalendar("holiday", "#C2410C")
            ws.createTask(stats.id, Workspace.TaskFields("HW5", due = Due(LocalDate.parse("2026-10-13"), null), done = true, categories = listOf("assignment"), sourceRef = "plms:assign:1"))
            ws.createEvent(stats.id, Workspace.EventFields("확률및통계", start = java.time.Instant.parse("2026-09-08T03:30:00Z"), end = java.time.Instant.parse("2026-09-08T04:45:00Z"), categories = listOf("class"), rrule = "FREQ=WEEKLY;BYDAY=TU,TH;COUNT=4"))
            ws.createEvent(holiday.id, Workspace.EventFields("한글날", startDate = LocalDate.parse("2026-10-09"), endDate = LocalDate.parse("2026-10-09"), sourceRef = "ical:kr:1"))
        }
        @Suppress("UNCHECKED_CAST")
        val report = client.post().uri("/api/v1/admin/restructure").exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!! as Map<String, Any?>
        assertThat(report).containsEntry("movedToPlms", 1).containsEntry("movedToAssistant", 1).containsEntry("feedEventsDropped", 1).containsEntry("calendarsRemoved", 2)

        assertThat(getList("/api/v1/calendars").map { it["name"] }).containsExactlyInAnyOrder("Assistant", "PLMS")
        val hw = getList("/api/v1/tasks?status=all").single()
        assertThat(hw["calendarName"]).isEqualTo("PLMS")
        assertThat(hw["categories"]).isEqualTo(listOf("확률및통계", "assignment"))
        assertThat(hw["done"]).isEqualTo(true)
        val lecture = getList("/api/v1/agenda?from=2026-09-07&to=2026-09-13").first()
        assertThat(lecture["calendarName"]).isEqualTo("Assistant")
        assertThat(lecture["categories"]).isEqualTo(listOf("확률및통계", "class"))

        @Suppress("UNCHECKED_CAST")
        val again = client.post().uri("/api/v1/admin/restructure").exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!! as Map<String, Any?>
        assertThat(again).containsEntry("calendarsRemoved", 0)
    }
}
