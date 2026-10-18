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
    fun `the feed has events and open deadlines from CalDAV, and rotating revokes the old URL`() {
        val cal = calendar("Lab")
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("calendarId" to cal, "title" to "Meeting", "start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00"))
            .exchange().expectStatus().isCreated
        post("/api/v1/tasks", mapOf("calendarId" to cal, "title" to "HW6", "due" to mapOf("date" to "2026-10-20", "time" to "19:00")))
        val old = post("/api/v1/tasks", mapOf("calendarId" to cal, "title" to "Old HW", "due" to mapOf("date" to "2026-10-19")))
        patch("/api/v1/tasks/${old["id"]}", mapOf("done" to true))

        val path = get("/api/v1/ical/feed")["path"] as String
        val ics = feedText(path)
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n")
        assertThat(ics).contains("SUMMARY:Meeting", "DTSTART:20261022T060000Z", "SUMMARY:Due: HW6", "DTSTART:20261020T100000Z")
        assertThat(ics).doesNotContain("Old HW")

        val rotated = client.post().uri("/api/v1/ical/feed/rotate").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["path"] as String
        assertThat(rotated).isNotEqualTo(path)
        client.get().uri(path).exchange().expectStatus().isNotFound
        feedText(rotated)
    }
}
