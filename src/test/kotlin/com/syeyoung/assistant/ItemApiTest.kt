package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class ItemApiTest : ApiTestBase() {

    @Test
    fun `creates an item with a deadline and main tag`() {
        val course = tag("CSED123", "#3b82f6")
        val assignment = tag("assignment", "#F97316")

        val item = post(
            "/api/v1/items",
            mapOf(
                "title" to "DB Assignment 3",
                "by" to mapOf("date" to "2026-10-23", "time" to "23:59"),
                "mainTagId" to course,
                "tagIds" to listOf(assignment),
            ),
        )

        assertThat(item["by"]).isEqualTo(mapOf("date" to "2026-10-23", "time" to "23:59:00"))
        assertThat(item["at"]).isNull()
        // The main tag is always part of the tag list.
        assertThat(item["tagIds"] as List<*>).containsExactlyInAnyOrder(course, assignment)
        assertThat(item.derived()["color"]).isEqualTo("#3B82F6")
        assertThat(item.derived()["overdue"]).isEqualTo(false)

        client.get().uri("/api/v1/items/${item["id"]}").exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"1\"")
    }

    @Test
    fun `work sessions inherit title and color and add up time`() {
        val course = tag("CSED123", "#3B82F6")
        val parent = post("/api/v1/items", mapOf("title" to "DB Assignment 3", "mainTagId" to course))
        val id = parent["id"]

        // Yesterday 14–16 (over, counts as spent) and tomorrow 10–13 (planned).
        post("/api/v1/items", mapOf("parentId" to id, "at" to timed("2026-10-17T14:00:00+09:00", "2026-10-17T16:00:00+09:00")))
        val upcoming = post(
            "/api/v1/items",
            mapOf("parentId" to id, "title" to "Q4–6", "at" to timed("2026-10-19T10:00:00+09:00", "2026-10-19T13:00:00+09:00")),
        )

        val reloaded = get("/api/v1/items/$id")
        assertThat(reloaded.derived()["timeSpentMinutes"]).isEqualTo(120)
        assertThat(reloaded.derived()["timePlannedMinutes"]).isEqualTo(180)
        assertThat(reloaded.derived()["subItems"]).isEqualTo(2)
        assertThat(reloaded.derived()["subItemsDone"]).isEqualTo(0)

        assertThat(upcoming.derived()["color"]).isEqualTo("#3B82F6")
        assertThat(upcoming.derived()["displayTitle"]).isEqualTo("Q4–6")

        val descendants = getList("/api/v1/items/$id/descendants")
        assertThat(descendants.map { it.derived()["displayTitle"] }).containsExactly("DB Assignment 3", "Q4–6")
    }

    @Test
    fun `patch is a merge patch and respects If-Match`() {
        val item = post(
            "/api/v1/items",
            mapOf("title" to "Meeting", "notes" to "bring slides", "at" to timed("2026-10-22T15:00:00+09:00", "2026-10-22T16:00:00+09:00")),
        )
        val id = item["id"]

        val updated = patch("/api/v1/items/$id", mapOf("title" to "Meeting with Prof. Kim", "at" to null), ifMatch = 1)
        assertThat(updated["title"]).isEqualTo("Meeting with Prof. Kim")
        assertThat(updated["notes"]).isEqualTo("bring slides")
        assertThat(updated["at"]).isNull()
        assertThat(updated["version"]).isEqualTo(2)

        // A stale version is rejected.
        client.patch().uri("/api/v1/items/$id").contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", "\"1\"").bodyValue(mapOf("title" to "x"))
            .exchange().expectStatus().isEqualTo(412)
            .expectBody().jsonPath("$.currentVersion").isEqualTo(2)
    }

    @Test
    fun `completing a parent completes its descendants but undoing does not`() {
        val parent = post("/api/v1/items", mapOf("title" to "Midterm prep"))
        val child = post("/api/v1/items", mapOf("title" to "Chapter 1", "parentId" to parent["id"]))
        val grandchild = post("/api/v1/items", mapOf("title" to "Exercises", "parentId" to child["id"]))

        patch("/api/v1/items/${parent["id"]}", mapOf("doneAt" to "2026-10-18T01:00:00Z"))
        assertThat(get("/api/v1/items/${grandchild["id"]}")["doneAt"]).isNotNull()

        patch("/api/v1/items/${parent["id"]}", mapOf("doneAt" to null))
        assertThat(get("/api/v1/items/${parent["id"]}")["doneAt"]).isNull()
        assertThat(get("/api/v1/items/${child["id"]}")["doneAt"]).isNotNull()
    }

    @Test
    fun `delete removes the whole subtree and restore brings it back`() {
        val parent = post("/api/v1/items", mapOf("title" to "Lab report"))
        val child = post("/api/v1/items", mapOf("title" to "Draft", "parentId" to parent["id"]))

        client.delete().uri("/api/v1/items/${parent["id"]}").exchange().expectStatus().isNoContent
        client.get().uri("/api/v1/items/${child["id"]}").exchange().expectStatus().isNotFound

        client.post().uri("/api/v1/items/${parent["id"]}/restore").exchange().expectStatus().isOk
        assertThat(get("/api/v1/items/${child["id"]}")["title"]).isEqualTo("Draft")
    }

    @Test
    fun `rejects cycles, bad times and unknown tags`() {
        val a = post("/api/v1/items", mapOf("title" to "A"))
        val b = post("/api/v1/items", mapOf("title" to "B", "parentId" to a["id"]))

        client.patch().uri("/api/v1/items/${a["id"]}").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("parentId" to b["id"])).exchange().expectStatus().isBadRequest

        client.post().uri("/api/v1/items").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "x", "at" to timed("2026-10-19T12:00:00+09:00", "2026-10-19T11:00:00+09:00")))
            .exchange().expectStatus().isBadRequest

        client.post().uri("/api/v1/items").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "x", "tagIds" to listOf("00000000-0000-0000-0000-000000000001")))
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `overdue is derived and filterable`() {
        // The clock is Sunday 10:00. A date-only deadline yesterday is overdue; one today is not yet.
        val late = post("/api/v1/items", mapOf("title" to "Scholarship form", "by" to mapOf("date" to "2026-10-17")))
        post("/api/v1/items", mapOf("title" to "Due tonight", "by" to mapOf("date" to "2026-10-18")))
        val earlierToday = post("/api/v1/items", mapOf("title" to "Due 09:00", "by" to mapOf("date" to "2026-10-18", "time" to "09:00")))
        post("/api/v1/items", mapOf("title" to "Done already", "by" to mapOf("date" to "2026-10-10"), "doneAt" to "2026-10-09T12:00:00Z"))

        assertThat(late.derived()["overdue"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val overdue = get("/api/v1/items?overdue=true")["items"] as List<Map<String, Any?>>
        assertThat(overdue.map { it["id"] }).containsExactlyInAnyOrder(late["id"], earlierToday["id"])
    }

    @Test
    fun `lists with cursor pagination`() {
        repeat(5) { post("/api/v1/items", mapOf("title" to "Item $it")) }
        val first = get("/api/v1/items?limit=3")
        assertThat(first["items"] as List<*>).hasSize(3)
        val second = get("/api/v1/items?limit=3&cursor=${first["nextCursor"]}")
        assertThat(second["items"] as List<*>).hasSize(2)
        assertThat(second["nextCursor"]).isNull()
    }

    private fun timed(start: String, end: String) = mapOf("start" to start, "end" to end)

    @Test
    fun `lists can hide items with given tags`() {
        val cls = tag("class", "#6B7280")
        post("/api/v1/items", mapOf("title" to "Lecture", "tagIds" to listOf(cls)))
        post("/api/v1/items", mapOf("title" to "HW", "by" to mapOf("date" to "2026-10-23")))
        @Suppress("UNCHECKED_CAST")
        val shown = get("/api/v1/items?hideTags=$cls")["items"] as List<Map<String, Any?>>
        assertThat(shown.map { it["title"] }).containsExactly("HW")
    }
}
