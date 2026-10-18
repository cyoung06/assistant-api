package com.syeyoung.assistant

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await

class MigrationTest : ApiTestBase() {

    private fun sql(s: String) = runBlocking { db.sql(s).await() }

    @Suppress("UNCHECKED_CAST")
    private fun migrate(): Map<String, Any?> =
        client.post().uri("/api/v1/admin/migrate-to-dav").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Test
    fun `moves tags, items and series into CalDAV once`() {
        val stats = "11111111-1111-1111-1111-111111111111"
        val asg = "22222222-2222-2222-2222-222222222222"
        val cls = "33333333-3333-3333-3333-333333333333"
        sql("insert into tags (id, name, color) values ('$stats', '확률및통계', '#2F5BE0'), ('$asg', 'assignment', '#6B7280'), ('$cls', 'class', '#6B7280')")
        // A PLMS deadline (done), a manual task with a work session, and a sub-task.
        sql(
            """
            insert into items (id, title, by_date, by_time, main_tag_id, tag_ids, done_at, source_type, source_ref, source_url) values
              ('aaaaaaaa-0000-0000-0000-000000000001', 'HW5', '2026-10-13', '19:00', '$stats', '{$stats,$asg}', '2026-10-12T00:00:00Z', 'plms', 'assign:238670', 'https://plms.example/5'),
              ('aaaaaaaa-0000-0000-0000-000000000002', 'Project', '2026-10-30', null, null, '{}', null, 'manual', null, null)
            """,
        )
        sql("insert into items (id, title, parent_id, at_start, at_end) values ('aaaaaaaa-0000-0000-0000-000000000003', '', 'aaaaaaaa-0000-0000-0000-000000000002', '2026-10-17T05:00:00Z', '2026-10-17T07:00:00Z')")
        sql("insert into items (id, title, parent_id) values ('aaaaaaaa-0000-0000-0000-000000000004', 'Outline', 'aaaaaaaa-0000-0000-0000-000000000002')")
        // A Tue/Thu class with one lecture deleted on its own (a holiday).
        sql(
            """
            insert into series (id, title, main_tag_id, tag_ids, all_day, start_time, duration_minutes, freq, interval, weekdays, start_date, until_date)
            values ('bbbbbbbb-0000-0000-0000-000000000001', '확률및통계', '$stats', '{$stats,$cls}', false, '12:30', 75, 'weekly', 1, '{2,4}', '2026-09-08', '2026-12-25')
            """,
        )
        sql(
            """
            insert into items (id, title, series_id, occurrence_date, detached, at_start, at_end, deleted_at) values
              ('cccccccc-0000-0000-0000-000000000001', '확률및통계', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-10-08', true, '2026-10-08T03:30:00Z', '2026-10-08T04:45:00Z', now())
            """,
        )
        sql("insert into source_tags (source_type, external_key, tag_id) values ('plms', 'course:15', '$stats')")

        val report = migrate()
        assertThat(report).containsEntry("series", 1).containsEntry("tasks", 3).containsEntry("events", 1)

        assertThat(getList("/api/v1/calendars").map { it["name"] }).containsExactlyInAnyOrder("확률및통계", "Inbox")
        val tasks = getList("/api/v1/tasks?status=all").associateBy { it["title"] }
        assertThat(tasks["HW5"]!!["done"]).isEqualTo(true)
        assertThat(tasks["HW5"]!!["source"]).isEqualTo("plms")
        assertThat(tasks["HW5"]!!["categories"]).isEqualTo(listOf("assignment"))
        assertThat(tasks["HW5"]!!["calendarName"]).isEqualTo("확률및통계")
        assertThat(tasks["Project"]!!["subtasks"]).isEqualTo(1)
        assertThat(tasks["Project"]!!["minutesSpent"]).isEqualTo(120)

        val weeks = getList("/api/v1/agenda?from=2026-10-04&to=2026-10-17").filter { it["repeats"] == true }
        assertThat(weeks.map { it["start"] }).containsExactly("2026-10-06T12:30:00+09:00", "2026-10-13T12:30:00+09:00", "2026-10-15T12:30:00+09:00")
        assertThat(weeks.first()["categories"]).isEqualTo(listOf("class"))

        // Running again changes nothing.
        assertThat(migrate()).containsEntry("calendars", 0).containsEntry("tasks", 0).containsEntry("series", 0)
        assertThat(getList("/api/v1/tasks?status=all")).hasSize(3)
    }
}
