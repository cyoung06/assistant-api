package com.syeyoung.assistant

import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.tasks.TaskSourceSync
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class CourseProjectsTest : ApiTestBase() {
    @Autowired lateinit var sync: TaskSourceSync

    private fun courses(vararg tags: SourceTag) = runBlocking { sync.syncProjects(SourceType.PLMS, tags.toList(), Instant.now()) }

    private fun projects() = getList("/api/v1/projects").associate { it["name"] to it["state"] }

    @Test
    fun `every course gets a project, sections share one, deleted ones stay deleted, ended ones complete`() {
        assertThat(courses(SourceTag("course:1", "고급 알고리즘"), SourceTag("course:2", "고급 알고리즘"), SourceTag("course:3", "일반생명과학"))).isEqualTo(2)
        assertThat(projects()).isEqualTo(mapOf("고급 알고리즘" to "in_progress", "일반생명과학" to "in_progress"))

        val bio = getList("/api/v1/projects").single { it["name"] == "일반생명과학" }
        delete("/api/v1/projects/${bio["id"]}")
        assertThat(courses(SourceTag("course:1", "고급 알고리즘"), SourceTag("course:3", "일반생명과학"))).isEqualTo(0)
        assertThat(projects()).doesNotContainKey("일반생명과학")

        // Next semester: the course is no longer listed.
        courses(SourceTag("course:9", "확률및통계"))
        assertThat(projects()).isEqualTo(mapOf("고급 알고리즘" to "completed", "확률및통계" to "in_progress"))
    }

    @Test
    fun `courses start out in their semester's initiative, once`() {
        courses(SourceTag("course:1", "고급 알고리즘", "2026 Fall"), SourceTag("course:2", "일반생명과학", "2026 Fall"))
        val fall = getList("/api/v1/initiatives").single()
        assertThat(fall).containsEntry("name", "2026 Fall").containsEntry("status", "active")
        assertThat(fall["projectIds"] as List<*>).hasSize(2)

        // Taken out by hand, it stays out.
        val algo = getList("/api/v1/projects").single { it["name"] == "고급 알고리즘" }
        patch("/api/v1/projects/${algo["id"]}", mapOf("initiativeIds" to emptyList<String>()))
        courses(SourceTag("course:1", "고급 알고리즘", "2026 Fall"), SourceTag("course:2", "일반생명과학", "2026 Fall"))
        assertThat(getList("/api/v1/initiatives").single()["projectIds"] as List<*>).hasSize(1)
    }

    @Test
    fun `semesters follow POSTECH's calendar`() {
        val semester = { d: String -> com.syeyoung.assistant.source.plms.PlmsSource.semester(java.time.LocalDate.parse(d)) }
        assertThat(listOf("2026-02-27", "2026-06-22", "2026-08-31", "2026-12-21", "2027-01-04").map(semester))
            .containsExactly("2026 Spring", "2026 Summer", "2026 Fall", "2026 Winter", "2026 Winter")
    }
}
