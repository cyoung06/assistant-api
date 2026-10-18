package com.syeyoung.assistant

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class CalendarApiTest : ApiTestBase() {

    private fun raw(id: String): String = runBlocking { dav.get(ws.findEvent(id)!!.href)!!.data }

    private fun events(from: String, to: String) = getList("/api/v1/events?from=$from&to=$to")

    @Test
    fun `one-off events are created, moved and deleted`() {
        val meeting = post("/api/v1/events", mapOf("title" to "Meeting", "start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00"))
        val id = meeting["id"] as String
        assertThat(meeting["calendarName"]).isEqualTo("Assistant")
        assertThat(raw(id)).contains("BEGIN:VEVENT", "SUMMARY:Meeting", "DTSTART:20261022T060000Z")

        val moved = patch("/api/v1/events/$id", mapOf("title" to "Meeting w/ prof", "start" to "2026-10-23T10:00:00+09:00", "end" to "2026-10-23T11:30:00+09:00"))
        assertThat(moved["start"]).isEqualTo("2026-10-23T10:00:00+09:00")
        assertThat(events("2026-10-18", "2026-10-24").map { it["title"] }).containsExactly("Meeting w/ prof")

        val allDay = post("/api/v1/events", mapOf("title" to "Festival", "startDate" to "2026-10-24", "endDate" to "2026-10-25"))
        assertThat(raw(allDay["id"] as String)).contains("DTSTART;VALUE=DATE:20261024", "DTEND;VALUE=DATE:20261026")
        delete("/api/v1/events/$id")
        assertThat(events("2026-10-18", "2026-10-24").map { it["title"] }).containsExactly("Festival")
    }

    @Test
    fun `classes repeat weekly, can change time and skip a date`() {
        val cls = post(
            "/api/v1/events",
            mapOf(
                "title" to "확률및통계", "start" to "2026-09-08T12:30:00+09:00", "end" to "2026-09-08T13:45:00+09:00",
                "repeat" to mapOf("freq" to "weekly", "weekdays" to listOf("TUESDAY", "THURSDAY"), "until" to "2026-12-18"),
            ),
        )
        val id = cls["id"] as String
        assertThat(raw(id)).contains("BEGIN:VTIMEZONE", "DTSTART;TZID=Asia/Seoul:20260908T123000", "RRULE:FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261218T145959Z")
        assertThat(events("2026-10-18", "2026-10-24").map { it["start"] }).containsExactly("2026-10-20T12:30:00+09:00", "2026-10-22T12:30:00+09:00")

        val repeat = (cls["repeat"] as Map<*, *>) + ("skipped" to listOf("2026-10-22"))
        val updated = patch("/api/v1/events/$id", mapOf("start" to "2026-09-08T13:00:00+09:00", "end" to "2026-09-08T14:15:00+09:00", "repeat" to repeat))
        @Suppress("UNCHECKED_CAST")
        assertThat(updated["repeat"] as Map<String, Any?>).containsEntry("skipped", listOf("2026-10-22")).containsEntry("weekdays", listOf("TUESDAY", "THURSDAY"))
        assertThat(events("2026-10-18", "2026-10-24").map { it["start"] }).containsExactly("2026-10-20T13:00:00+09:00")

        patch("/api/v1/events/$id", mapOf("repeat" to null))
        assertThat(raw(id)).doesNotContain("RRULE", "EXDATE")
    }

    @Test
    fun `events from other apps keep their extras and odd rules stay read-only`() {
        putRaw(
            "from-mac.ics",
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//macOS//EN
            BEGIN:VEVENT
            UID:birthday
            DTSTART;VALUE=DATE:20261020
            DTEND;VALUE=DATE:20261021
            RRULE:FREQ=YEARLY
            SUMMARY:Mom's birthday
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """,
        )
        val e = get("/api/v1/events/birthday")
        assertThat(e["repeats"]).isEqualTo(true)
        assertThat(e).doesNotContainKey("repeat")
        patch("/api/v1/events/birthday", mapOf("notes" to "call her"))
        assertThat(raw("birthday")).contains("DESCRIPTION:call her", "RRULE:FREQ=YEARLY", "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC", "BEGIN:VALARM")
    }

    @Test
    fun `bad input is refused`() {
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "x", "start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T14:00:00+09:00"))
            .exchange().expectStatus().isBadRequest
        client.get().uri("/api/v1/events?from=2026-10-20&to=2026-10-19").exchange().expectStatus().isBadRequest
    }
}
