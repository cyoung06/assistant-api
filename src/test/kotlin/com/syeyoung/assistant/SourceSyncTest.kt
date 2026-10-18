package com.syeyoung.assistant

import com.syeyoung.assistant.item.SourceType
import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A source whose snapshot each test sets. Uses the `email` type so it can't collide with PLMS. */
class FakeSource : DataSource {
    override val type = SourceType.EMAIL
    override val displayName = "Fake"
    override val enabled = true
    override val interval: Duration = Duration.ofHours(1)
    var snapshot = SourceSnapshot(emptyList())
    var failWith: String? = null
    override suspend fun fetch(now: Instant): SourceSnapshot {
        failWith?.let { error(it) }
        return snapshot
    }
}

class SourceSyncTest : ApiTestBase() {
    @Autowired lateinit var fake: FakeSource

    @BeforeEach
    fun reset() {
        fake.snapshot = SourceSnapshot(emptyList())
        fake.failWith = null
    }

    private fun record(ref: String, title: String, due: String, vararg tags: SourceTag) =
        SourceRecord(ref, title, LocalDate.parse(due), LocalTime.of(23, 59), "https://example.test/$ref", tags.toList())

    private val stats = SourceTag("course:15", "확률및통계")

    @Suppress("UNCHECKED_CAST")
    private fun sync(): Map<String, Any?> =
        client.post().uri("/api/v1/sources/email/sync").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun itemsFromSource(): List<Map<String, Any?>> =
        (get("/api/v1/items?limit=200")["items"] as List<Map<String, Any?>>).filter { (it["source"] as Map<*, *>)["type"] == "email" }

    @Test
    fun `creates items with course tags, then updates only what the source owns`() {
        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "HW6", "2026-10-20", stats, SourceTag("module:assign", "assignment"))))
        val first = sync()
        assertThat((first["lastReport"] as Map<*, *>)["created"]).isEqualTo(1)

        val item = itemsFromSource().single()
        val tags = getList("/api/v1/tags").associate { it["name"] to it["id"] }
        assertThat(item["mainTagId"]).isEqualTo(tags["확률및통계"])
        assertThat(item["tagIds"] as List<*>).containsExactlyInAnyOrder(tags["확률및통계"], tags["assignment"])

        // The user adds a note and a tag of their own; PLMS then moves the deadline.
        val mine = tag("important", "#D13B3B")
        patch("/api/v1/items/${item["id"]}", mapOf("notes" to "start early", "tagIds" to (item["tagIds"] as List<*>) + mine))
        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "HW6 (revised)", "2026-10-22", stats)))
        val second = sync()
        assertThat((second["lastReport"] as Map<*, *>)["updated"]).isEqualTo(1)

        val updated = itemsFromSource().single()
        assertThat(updated["title"]).isEqualTo("HW6 (revised)")
        assertThat((updated["by"] as Map<*, *>)["date"]).isEqualTo("2026-10-22")
        assertThat(updated["notes"]).isEqualTo("start early")
        assertThat(updated["tagIds"] as List<*>).contains(mine)
    }

    @Test
    fun `merged sections share one tag, and a synced twice item is not duplicated`() {
        fake.snapshot = SourceSnapshot(
            listOf(
                record("assign:1", "A", "2026-10-20", SourceTag("course:1", "고급 알고리즘")),
                record("assign:2", "B", "2026-10-21", SourceTag("course:2", "고급 알고리즘")),
            ),
        )
        sync()
        sync()
        assertThat(itemsFromSource()).hasSize(2)
        assertThat(getList("/api/v1/tags").filter { it["name"] == "고급 알고리즘" }).hasSize(1)
    }

    @Test
    fun `items that disappear from the source are completed, deleted ones stay deleted`() {
        // The test clock is 2026-10-18 10:00.
        val window = Instant.parse("2026-10-04T01:00:00Z")
        fake.snapshot = SourceSnapshot(
            listOf(record("assign:1", "Submitted", "2026-10-20"), record("assign:2", "Deleted by me", "2026-10-21"), record("assign:3", "Still open", "2026-10-22")),
            completeMissingDueFrom = window,
        )
        sync()
        val byTitle = itemsFromSource().associateBy { it["title"] }
        client.delete().uri("/api/v1/items/${byTitle["Deleted by me"]!!["id"]}").exchange().expectStatus().isNoContent

        // Assignment 1 was submitted, so PLMS no longer lists it; 2 is still listed but deleted in the app.
        fake.snapshot = SourceSnapshot(
            listOf(record("assign:2", "Deleted by me", "2026-10-21"), record("assign:3", "Still open", "2026-10-22")),
            completeMissingDueFrom = window,
        )
        val report = sync()["lastReport"] as Map<*, *>
        assertThat(report["completed"]).isEqualTo(1)
        assertThat(report["skippedDeleted"]).isEqualTo(1)

        val after = itemsFromSource().associateBy { it["title"] }
        assertThat(after["Submitted"]!!["doneAt"]).isNotNull()
        assertThat(after["Still open"]!!["doneAt"]).isNull()
        assertThat(after).doesNotContainKey("Deleted by me")
    }

    @Test
    fun `a tag the user deleted is not recreated`() {
        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "A", "2026-10-20", stats)))
        sync()
        val tagId = getList("/api/v1/tags").single { it["name"] == "확률및통계" }["id"]
        client.delete().uri("/api/v1/tags/$tagId").exchange().expectStatus().isNoContent

        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "A", "2026-10-20", stats), record("assign:2", "B", "2026-10-21", stats)))
        sync()
        assertThat(getList("/api/v1/tags").map { it["name"] }).doesNotContain("확률및통계")
        assertThat(itemsFromSource().single { it["title"] == "B" }["tagIds"] as List<*>).isEmpty()
    }

    @Test
    fun `a failing source records the error and keeps going`() {
        fake.failWith = "PLMS login failed: check the username and password"
        val status = sync()
        assertThat(status["lastError"]).isEqualTo("PLMS login failed: check the username and password")
        assertThat(status["lastSuccessAt"]).isNull()

        fake.failWith = null
        val ok = sync()
        assertThat(ok["lastError"]).isNull()
        assertThat(ok["lastSuccessAt"]).isNotNull()

        val list = getList("/api/v1/sources")
        assertThat(list.map { it["type"] }).contains("plms", "email")
        assertThat(list.single { it["type"] == "plms" }["enabled"]).isEqualTo(false)
    }

    @Test
    fun `unknown sources are 404`() {
        client.post().uri("/api/v1/sources/nope/sync").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isNotFound
    }
}
