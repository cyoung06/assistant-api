package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeedTest : ApiTestBase() {
    private fun feedText(path: String): String =
        client.get().uri(path).exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith("text/calendar")
            .expectBody(String::class.java).returnResult().responseBody!!

    @Test
    fun `the feed has events, open deadlines and classes, and rotating revokes the old URL`() {
        post("/api/v1/items", mapOf("title" to "Meeting", "at" to mapOf("start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00")))
        post("/api/v1/items", mapOf("title" to "HW6", "by" to mapOf("date" to "2026-10-20", "time" to "19:00")))
        post("/api/v1/items", mapOf("title" to "Old HW", "by" to mapOf("date" to "2026-10-19"), "doneAt" to "2026-10-18T00:00:00Z"))
        post(
            "/api/v1/items",
            mapOf(
                "title" to "확률및통계",
                "at" to mapOf("start" to "2026-10-20T12:30:00+09:00", "end" to "2026-10-20T13:45:00+09:00"),
                "repeat" to mapOf("freq" to "weekly", "weekdays" to listOf("TUESDAY", "THURSDAY"), "count" to 4),
            ),
        )
        post("/api/v1/items", mapOf("title" to "한글날", "at" to mapOf("allDay" to true, "startDate" to "2026-10-09", "endDate" to "2026-10-09"), "source" to mapOf("type" to "ical", "ref" to "kr:1")))

        val path = get("/api/v1/ical/feed")["path"] as String
        assertThat(get("/api/v1/ical/feed")["path"]).isEqualTo(path)
        val ics = feedText(path)
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n")
        assertThat(ics).contains("SUMMARY:Meeting", "DTSTART:20261022T060000Z", "SUMMARY:Due: HW6", "DTSTART:20261020T100000Z")
        assertThat(ics.split("SUMMARY:확률및통계").size - 1).isEqualTo(4)
        // Done deadlines and events imported from other calendars stay out.
        assertThat(ics).doesNotContain("Old HW").doesNotContain("한글날")

        val rotated = post2xx("/api/v1/ical/feed/rotate")["path"] as String
        assertThat(rotated).isNotEqualTo(path)
        client.get().uri(path).exchange().expectStatus().isNotFound
        feedText(rotated)
    }

    @Suppress("UNCHECKED_CAST")
    private fun post2xx(path: String): Map<String, Any?> =
        client.post().uri(path).exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>
}
