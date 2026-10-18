package com.syeyoung.assistant

import com.syeyoung.assistant.source.SourceType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import com.syeyoung.assistant.source.SourceApplier
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class MoveFromCalDavTest : ApiTestBase() {
    @Autowired lateinit var applier: SourceApplier

    @Suppress("UNCHECKED_CAST")
    private fun move() = client.post().uri("/api/v1/admin/move-tasks").exchange().expectStatus().isOk
        .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Test
    fun `to-dos move out of CalDAV, and the source's next sync matches them`() {
        putRaw(
            "plms.ics",
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//assistant//EN
            BEGIN:VTODO
            UID:hw6@assistant
            SUMMARY:HW6
            DUE:20261020T145900Z
            STATUS:COMPLETED
            COMPLETED:20261019T120000Z
            CATEGORIES:확률및통계,assignment
            URL:https://plms.example/assign/1
            X-ASSISTANT-SOURCE:plms:assign:1
            END:VTODO
            END:VCALENDAR
            """,
        )
        putRaw(
            "mine.ics",
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//assistant//EN
            BEGIN:VTODO
            UID:sub@assistant
            SUMMARY:Read the slides
            RELATED-TO;RELTYPE=PARENT:hw6@assistant
            CATEGORIES:study
            END:VTODO
            END:VCALENDAR
            """,
        )
        assertThat(move()).containsEntry("moved", 2).containsEntry("projects", 1).containsEntry("labels", 2)
        assertThat(runBlocking { ws.snapshot().docs.values.count { it.main?.name == "VTODO" } }).isZero()

        val tasks = getList("/api/v1/tasks").associateBy { it["title"] }
        val hw = tasks.getValue("HW6")
        assertThat(hw).containsEntry("status", "done").containsEntry("source", "plms").containsEntry("closedAt", "2026-10-19T12:00:00Z")
        assertThat(hw["due"]).isEqualTo(mapOf("date" to "2026-10-20", "time" to "23:59:00"))
        assertThat(tasks.getValue("Read the slides")["parentId"]).isEqualTo(hw["id"])

        // PLMS's next sync claims the course project and leaves the moved task as it is.
        val record = SourceRecord(
            "assign:1", "HW6", LocalDate.parse("2026-10-20"), LocalTime.of(23, 59), url = "https://plms.example/assign/1",
            tags = listOf(SourceTag("module:assign", "assignment")), project = SourceTag("course:15", "확률및통계"),
        )
        val report = runBlocking { applier.apply(SourceType.PLMS, "PLMS", SourceSnapshot(listOf(record)), Instant.now()) }
        assertThat(report.unchanged).isEqualTo(1)
        assertThat(getList("/api/v1/projects")).hasSize(1)
        assertThat(move()).containsEntry("moved", 0)
    }
}
