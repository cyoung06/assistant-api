package com.syeyoung.assistant

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await

/** What a mention in a note (`@Name`) means, and the notes mentioning a thing by name. */
class MentionsTest : ApiTestBase() {
    @org.junit.jupiter.api.BeforeEach
    fun clearIndex() = runBlocking {
        db.sql("delete from note_links").await()
        db.sql("delete from drive_index").await()
    }

    private fun note(path: String, modified: String = "2026-10-18T00:00:00Z", vararg mentions: String) = runBlocking {
        db.sql("insert into drive_index (path, name, folder, modified) values (:p, :n, false, :m::timestamptz)")
            .bind("p", path).bind("n", path.substringAfterLast('/')).bind("m", modified).await()
        for (m in mentions) db.sql("insert into note_links (note, target) values (:p, :t)").bind("p", path).bind("t", "mention:${m.lowercase()}").await()
    }

    /** GET [path] with [params] as its query (each encoded once, by the client). */
    @Suppress("UNCHECKED_CAST")
    private fun <T> query(path: String, vararg params: Pair<String, String>): T =
        client.get().uri { b -> params.fold(b.path(path)) { u, (k, _) -> u.queryParam(k, "{$k}") }.build(params.toMap()) }
            .exchange().expectStatus().isOk.expectBody(Any::class.java).returnResult().responseBody as T

    private fun mention(name: String, from: String? = null): Map<String, Any?> =
        query("/api/v1/refs/mention", *listOfNotNull("name" to name, from?.let { "from" to it }).toTypedArray())

    @Test
    fun `a name means the note's own project's file, then an open task, then a file anywhere`() {
        val done = post("/api/v1/tasks", mapOf("title" to "HW3"))
        patch("/api/v1/tasks/${done["id"]}", mapOf("status" to "done"))
        val open = post("/api/v1/tasks", mapOf("title" to "HW3"))
        note("Projects/확률및통계/Notes/HW3.md")
        note("Projects/Other/HW3.md", "2026-10-19T00:00:00Z")

        assertThat(mention("hw3", "Projects/확률및통계/Notes/today.md")["url"] as String).endsWith("/files/%ED%99%95%EB%A5%A0%EB%B0%8F%ED%86%B5%EA%B3%84/Notes/HW3.md")
        assertThat(mention("HW3", "Projects/Plain/Notes/today.md")["url"] as String).endsWith("/task/${open["id"]}")
        assertThat(mention("#${done["number"]}")["url"] as String).endsWith("/task/${done["id"]}")
        // Nothing called that.
        client.get().uri("/api/v1/refs/mention?name=nothing").exchange().expectStatus().isNotFound
    }

    @Test
    fun `a task's backlinks include the notes mentioning it, where the name means it`() {
        val task = post("/api/v1/tasks", mapOf("title" to "HW3"))
        note("Projects/확률및통계/Notes/HW3.md")
        // Here HW3 is the project's own note; there (no such note) it's the task.
        note("Projects/확률및통계/Notes/today.md", "2026-10-18T01:00:00Z", "HW3")
        note("Projects/Plain/Notes/plan.md", "2026-10-18T02:00:00Z", "hw3")
        note("Projects/Plain/Notes/by-number.md", "2026-10-18T03:00:00Z", "#${task["number"]}")
        val links: List<Map<String, Any?>> = query("/api/v1/refs/backlinks", "url" to "http://elsewhere/task/${task["id"]}")
        assertThat(links.map { it["title"] }).containsExactlyInAnyOrder("plan", "by-number")
        assertThat(links.map { it["kind"] }.toSet()).containsExactly("note")
    }
}
