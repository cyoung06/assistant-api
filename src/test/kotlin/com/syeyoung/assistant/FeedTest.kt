package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class FeedTest : ApiTestBase() {
    private fun feedText(path: String): String =
        client.get().uri(path).exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith("text/calendar")
            .expectBody(String::class.java).returnResult().responseBody!!

    @Test
    fun `the feed carries every stored object plus due events, and rotating revokes the old URL`() {
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Meeting", "start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00"))
            .exchange().expectStatus().isCreated
        post("/api/v1/tasks", mapOf("title" to "HW6", "due" to mapOf("date" to "2026-10-20", "time" to "19:00")))
        val old = post("/api/v1/tasks", mapOf("title" to "Old HW", "due" to mapOf("date" to "2026-10-19")))
        putRaw(
            "class.ics",
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:class
            DTSTART:20260908T033000Z
            DTEND:20260908T044500Z
            RRULE:FREQ=WEEKLY;BYDAY=TU,TH;COUNT=30
            SUMMARY:확률및통계
            END:VEVENT
            END:VCALENDAR
            """,
        )
        patch("/api/v1/tasks/${old["id"]}", mapOf("done" to true))

        val path = get("/api/v1/ical/feed")["path"] as String
        val ics = feedText(path)
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n")
        assertThat(ics).contains("SUMMARY:Meeting", "DTSTART:20261022T060000Z", "SUMMARY:Due: HW6", "DTSTART:20261020T100000Z")
        // Stored objects go out as they are: the repeat stays one RRULE event, to-dos stay VTODO.
        assertThat(ics).contains("RRULE:FREQ=WEEKLY;BYDAY=TU,TH;COUNT=30", "BEGIN:VTODO", "SUMMARY:Old HW")
        assertThat(ics.split("SUMMARY:확률및통계").size - 1).isEqualTo(1)
        // Done to-dos get no "Due" event.
        assertThat(ics).doesNotContain("Due: Old HW")

        val rotated = client.post().uri("/api/v1/ical/feed/rotate").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["path"] as String
        assertThat(rotated).isNotEqualTo(path)
        client.get().uri(path).exchange().expectStatus().isNotFound
        feedText(rotated)
    }
}
