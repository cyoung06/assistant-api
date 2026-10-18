package com.syeyoung.assistant

import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.SourceType
import kotlinx.coroutines.runBlocking
import org.springframework.r2dbc.core.await
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A source whose snapshot each test sets. Uses the `upload` type so it can't collide with real sources. */
class FakeSource : DataSource {
    override val type = SourceType.UPLOAD
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

    private val stats = SourceTag("course:15", "확률및통계")
    private val assignment = SourceTag("module:assign", "assignment")

    private fun record(ref: String, title: String, due: String, project: SourceTag? = stats, vararg labels: SourceTag) =
        SourceRecord(ref, title, LocalDate.parse(due), LocalTime.of(23, 59), url = "https://example.test/$ref", tags = labels.toList(), project = project)

    @Suppress("UNCHECKED_CAST")
    private fun sync(): Map<String, Any?> =
        client.post().uri("/api/v1/sources/upload/sync").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    private fun report() = sync()["lastReport"] as Map<*, *>

    private fun synced() = getList("/api/v1/tasks").filter { it["source"] == "upload" }

    @Test
    fun `creates tasks in a project with labels, then updates only what the source owns`() {
        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "HW6", "2026-10-20", stats, assignment)))
        assertThat(report()["created"]).isEqualTo(1)
        val task = synced().single()
        val project = getList("/api/v1/projects").single()
        assertThat(project).containsEntry("name", "확률및통계").containsEntry("source", "upload")
        assertThat(task["projectId"]).isEqualTo(project["id"])
        val label = getList("/api/v1/labels").single()
        assertThat(label["name"]).isEqualTo("assignment")
        assertThat(task["labelIds"]).isEqualTo(listOf(label["id"]))
        assertThat(task["url"]).isEqualTo("https://example.test/assign:1")
        val id = task["id"] as String

        // The user sets priority, status and a description and starts a sub-task; the source then moves the deadline.
        patch("/api/v1/tasks/$id", mapOf("priority" to "high", "status" to "in_progress", "description" to "start early"))
        // Title and deadline belong to the source.
        client.patch().uri("/api/v1/tasks/$id").bodyValue(mapOf("title" to "mine")).exchange().expectStatus().isEqualTo(409)
        post("/api/v1/tasks", mapOf("title" to "read chapter 5", "parentId" to id))
        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "HW6 (revised)", "2026-10-22", stats, assignment)))
        assertThat(report()["updated"]).isEqualTo(1)

        val updated = get("/api/v1/tasks/$id")
        assertThat(updated["title"]).isEqualTo("HW6 (revised)")
        assertThat((updated["due"] as Map<*, *>)["date"]).isEqualTo("2026-10-22")
        assertThat(updated).containsEntry("description", "start early").containsEntry("priority", "high").containsEntry("status", "in_progress")
        assertThat(updated["subtasks"]).isEqualTo(1)
        assertThat(report()["unchanged"]).isEqualTo(1)
    }

    @Test
    fun `missing tasks are completed and deleted ones stay deleted`() {
        val from = Instant.parse("2026-10-04T01:00:00Z")
        fake.snapshot = SourceSnapshot(
            listOf(record("assign:1", "Submitted", "2026-10-20"), record("assign:2", "Deleted by me", "2026-10-21"), record("assign:3", "Open", "2026-10-22", SourceTag("course:16", "확률및통계"))),
            completeMissingDueFrom = from,
        )
        sync()
        // Calendars are only for events.
        assertThat(getList("/api/v1/calendars")).isEmpty()
        val byTitle = synced().associateBy { it["title"] }
        delete("/api/v1/tasks/${byTitle["Deleted by me"]!!["id"]}")

        fake.snapshot = SourceSnapshot(listOf(record("assign:2", "Deleted by me", "2026-10-21"), record("assign:3", "Open", "2026-10-22")), completeMissingDueFrom = from)
        val r = report()
        assertThat(r["completed"]).isEqualTo(1)
        assertThat(r["skippedDeleted"]).isEqualTo(1)
        val after = synced().associateBy { it["title"] }
        assertThat(after["Submitted"]!!["status"]).isEqualTo("done")
        assertThat(after["Open"]!!["status"]).isEqualTo("todo")
        assertThat(after).doesNotContainKey("Deleted by me")
    }

    @Test
    fun `a same-named project from before is claimed instead of duplicated`() {
        runBlocking {
            db.sql("insert into projects (id, name, color, source_type) values (gen_random_uuid(), '확률및통계', '#2F5BE0', 'upload')").await()
        }
        fake.snapshot = SourceSnapshot(listOf(record("assign:1", "A", "2026-10-20"), record("assign:2", "B", "2026-10-21")))
        sync()
        val projects = getList("/api/v1/projects")
        assertThat(projects).hasSize(1)
        assertThat(synced().map { it["projectId"] }.toSet()).containsExactly(projects.single()["id"])
    }

    @Test
    fun `calendar feeds create events and delete the ones they no longer list`() {
        val holiday = SourceTag("ical:kr", "holiday")
        val cal = SourceTag("ical:kr", "Korean holidays")
        fun day(ref: String, title: String, date: String) = SourceRecord(ref, title, atStartDate = LocalDate.parse(date), atEndDate = LocalDate.parse(date), tags = listOf(holiday), calendar = cal)
        fake.snapshot = SourceSnapshot(listOf(day("kr:hangul", "한글날", "2026-10-09"), day("kr:xmas", "기독탄신일", "2026-12-25")), deleteMissing = true)
        sync()
        assertThat(getList("/api/v1/events?from=2026-10-01&to=2026-12-31").map { it["title"] }).containsExactly("한글날", "기독탄신일")

        fake.snapshot = SourceSnapshot(listOf(day("kr:hangul", "한글날", "2026-10-10")), deleteMissing = true)
        val r = report()
        assertThat(r["deleted"]).isEqualTo(1)
        assertThat(r["updated"]).isEqualTo(1)
        val after = getList("/api/v1/events?from=2026-10-01&to=2026-12-31")
        assertThat(after.map { it["title"] to it["startDate"] }).containsExactly("한글날" to "2026-10-10")
        assertThat(after.single()["calendarName"]).isEqualTo("Korean holidays")
        assertThat(after.single()["categories"]).isEqualTo(listOf("holiday"))
        assertThat(after.single()["fixed"]).isEqualTo(true)
        assertThat(getList("/api/v1/calendars").single()["fixed"]).isEqualTo(true)
        client.delete().uri("/api/v1/events/${after.single()["id"]}").exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `a failing source records the error and keeps going`() {
        fake.failWith = "PLMS login failed: check the username and password"
        assertThat(sync()["lastError"]).isEqualTo("PLMS login failed: check the username and password")
        fake.failWith = null
        val ok = sync()
        assertThat(ok["lastError"]).isNull()
        assertThat(ok["lastSuccessAt"]).isNotNull()
        assertThat(getList("/api/v1/sources").map { it["type"] }).contains("plms", "upload")
    }

    @Test
    fun `unknown sources are 404`() {
        client.post().uri("/api/v1/sources/nope/sync").exchange().expectStatus().isNotFound
    }
}
