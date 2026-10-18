package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class SeriesTest : ApiTestBase() {

    /** A Tue/Thu 09:30–10:45 class from Tue Oct 20 until Dec 17: 18 occurrences. */
    private fun createClass(title: String = "확률및통계 lecture"): Map<String, Any?> =
        post(
            "/api/v1/items",
            mapOf(
                "title" to title,
                "at" to mapOf("start" to "2026-10-20T09:30:00+09:00", "end" to "2026-10-20T10:45:00+09:00"),
                "repeat" to mapOf("freq" to "weekly", "weekdays" to listOf("TUESDAY", "THURSDAY"), "until" to "2026-12-17"),
            ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun occurrences(): List<Map<String, Any?>> =
        (get("/api/v1/items?limit=200")["items"] as List<Map<String, Any?>>)
            .filter { it["series"] != null }
            .sortedBy { (it["series"] as Map<*, *>)["occurrenceDate"] as String }

    private fun Map<String, Any?>.date() = (this["series"] as Map<*, *>)["occurrenceDate"] as String
    private fun Map<String, Any?>.start() = (this["at"] as Map<*, *>)["start"] as String
    private fun on(date: String) = occurrences().single { it.date() == date }

    private fun patchScoped(id: Any?, body: Any, scope: String) =
        client.patch().uri("/api/v1/items/$id?scope=$scope").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

    @Test
    fun `a weekly class fills its semester with real items`() {
        val first = createClass()
        assertThat((first["series"] as Map<*, *>)["occurrenceDate"]).isEqualTo("2026-10-20")

        val all = occurrences()
        assertThat(all).hasSize(18)
        assertThat(all.map { it.date() }).startsWith("2026-10-20", "2026-10-22", "2026-10-27").endsWith("2026-12-15", "2026-12-17")
        assertThat(on("2026-11-05").start()).isEqualTo("2026-11-05T09:30:00+09:00")
        assertThat(on("2026-11-05")["title"]).isEqualTo("확률및통계 lecture")

        val week = getList("/api/v1/calendar?from=2026-10-18&to=2026-10-25")
        assertThat(week.map { it["seriesId"] }.distinct()).containsExactly((first["series"] as Map<*, *>)["id"])
    }

    @Test
    fun `editing one occurrence detaches it from series-wide edits`() {
        createClass()
        // Nov 5's class moves to Wednesday Nov 4 (a make-up day).
        patchScoped(on("2026-11-05")["id"], mapOf("at" to mapOf("start" to "2026-11-04T15:00:00+09:00", "end" to "2026-11-04T16:15:00+09:00")), "this")
            .expectStatus().isOk
        // Then the whole series is renamed.
        patchScoped(on("2026-10-22")["id"], mapOf("title" to "Stats"), "all").expectStatus().isOk

        val moved = on("2026-11-05")
        assertThat(moved.start()).isEqualTo("2026-11-04T15:00:00+09:00")
        assertThat((moved["series"] as Map<*, *>)["detached"]).isEqualTo(true)
        assertThat(moved["title"]).isEqualTo("확률및통계 lecture")
        assertThat(occurrences().count { it["title"] == "Stats" }).isEqualTo(17)
    }

    @Test
    fun `this and following splits the series and keeps notes`() {
        createClass()
        patchScoped(on("2026-11-12")["id"], mapOf("notes" to "bring calculator"), "this").expectStatus().isOk

        // From Nov 10 on, the class moves to 11:00–12:15.
        patchScoped(on("2026-11-10")["id"], mapOf("at" to mapOf("start" to "2026-11-10T11:00:00+09:00", "end" to "2026-11-10T12:15:00+09:00")), "following")
            .expectStatus().isOk

        assertThat(on("2026-11-05").start()).isEqualTo("2026-11-05T09:30:00+09:00")
        assertThat(on("2026-11-10").start()).isEqualTo("2026-11-10T11:00:00+09:00")
        assertThat(on("2026-12-17").start()).isEqualTo("2026-12-17T11:00:00+09:00")
        assertThat(on("2026-11-12")["notes"]).isEqualTo("bring calculator")
        assertThat(occurrences()).hasSize(18)
        val seriesIds = occurrences().map { (it["series"] as Map<*, *>)["id"] }.distinct()
        assertThat(seriesIds).hasSize(2)
    }

    @Test
    fun `deleting one, the following, or all occurrences`() {
        createClass()
        // Holiday: no class on Thu Oct 29.
        client.delete().uri("/api/v1/items/${on("2026-10-29")["id"]}?scope=this").exchange().expectStatus().isNoContent
        assertThat(occurrences().map { it.date() }).doesNotContain("2026-10-29").hasSize(17)

        // The course ends early: nothing from Dec 1 on.
        client.delete().uri("/api/v1/items/${on("2026-12-01")["id"]}?scope=following").exchange().expectStatus().isNoContent
        assertThat(occurrences().map { it.date() }.last()).isEqualTo("2026-11-26")

        // Changing the rule regenerates, but the deleted holiday stays deleted.
        val seriesId = (on("2026-10-20")["series"] as Map<*, *>)["id"]
        client.put().uri("/api/v1/series/$seriesId/repeat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("freq" to "weekly", "weekdays" to listOf("TUESDAY", "THURSDAY"), "until" to "2026-12-17"))
            .exchange().expectStatus().isOk
        assertThat(occurrences().map { it.date() }).doesNotContain("2026-10-29").contains("2026-12-17")

        client.delete().uri("/api/v1/items/${on("2026-11-03")["id"]}?scope=all").exchange().expectStatus().isNoContent
        assertThat(occurrences()).isEmpty()
        client.get().uri("/api/v1/series/$seriesId").exchange().expectStatus().isNotFound
    }

    @Test
    fun `changing the repeat days replaces future occurrences but keeps done ones`() {
        createClass()
        patchScoped(on("2026-10-22")["id"], mapOf("doneAt" to "2026-10-22T02:00:00Z"), "this").expectStatus().isOk
        val seriesId = (on("2026-10-20")["series"] as Map<*, *>)["id"]

        // Now Tuesdays and Wednesdays.
        client.put().uri("/api/v1/series/$seriesId/repeat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("freq" to "weekly", "weekdays" to listOf("TUESDAY", "WEDNESDAY"), "until" to "2026-12-17"))
            .exchange().expectStatus().isOk

        val dates = occurrences().map { it.date() }
        assertThat(dates).contains("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-28").doesNotContain("2026-10-29")
        assertThat(on("2026-10-22")["doneAt"]).isNotNull()
    }

    @Test
    fun `an existing item can start repeating, with checks`() {
        val item = post("/api/v1/items", mapOf("title" to "Lab meeting", "at" to mapOf("start" to "2026-10-22T15:00:00+09:00", "end" to "2026-10-22T16:00:00+09:00")))
        client.post().uri("/api/v1/items/${item["id"]}/repeat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("freq" to "weekly", "count" to 4)).exchange().expectStatus().isOk
        assertThat(occurrences().map { it.date() }).containsExactly("2026-10-22", "2026-10-29", "2026-11-05", "2026-11-12")

        // Deadlines don't repeat, the first date must fit the rule, and scope must be known.
        val deadline = post("/api/v1/items", mapOf("title" to "HW", "by" to mapOf("date" to "2026-10-23")))
        client.post().uri("/api/v1/items/${deadline["id"]}/repeat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("freq" to "weekly")).exchange().expectStatus().isBadRequest
        val friday = post("/api/v1/items", mapOf("title" to "x", "at" to mapOf("start" to "2026-10-23T09:00:00+09:00", "end" to "2026-10-23T10:00:00+09:00")))
        client.post().uri("/api/v1/items/${friday["id"]}/repeat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("freq" to "weekly", "weekdays" to listOf("MONDAY"))).exchange().expectStatus().isBadRequest
        patchScoped(on("2026-10-29")["id"], mapOf("title" to "y"), "sometimes").expectStatus().isBadRequest
    }

    @Test
    fun `moving every occurrence to another day is refused`() {
        createClass()
        patchScoped(on("2026-10-27")["id"], mapOf("at" to mapOf("start" to "2026-10-28T09:30:00+09:00", "end" to "2026-10-28T10:45:00+09:00")), "all")
            .expectStatus().isBadRequest
    }

    @Test
    fun `endless series are generated about four months ahead`() {
        post(
            "/api/v1/items",
            mapOf(
                "title" to "Gym",
                "at" to mapOf("start" to "2026-10-19T07:00:00+09:00", "end" to "2026-10-19T08:00:00+09:00"),
                "repeat" to mapOf("freq" to "daily"),
            ),
        )
        val dates = occurrences().map { it.date() }
        assertThat(dates.first()).isEqualTo("2026-10-19")
        // Test clock: 2026-10-18; horizon 120 days.
        assertThat(dates.last()).isEqualTo("2027-02-15")
    }

    @Test
    fun `lists can leave repeating events out`() {
        createClass()
        post("/api/v1/items", mapOf("title" to "HW", "by" to mapOf("date" to "2026-10-23")))
        @Suppress("UNCHECKED_CAST")
        val oneOff = get("/api/v1/items?repeating=false")["items"] as List<Map<String, Any?>>
        assertThat(oneOff.map { it["title"] }).containsExactly("HW")
        @Suppress("UNCHECKED_CAST")
        val repeating = get("/api/v1/items?repeating=true&limit=200")["items"] as List<Map<String, Any?>>
        assertThat(repeating).hasSize(18)
    }
}
