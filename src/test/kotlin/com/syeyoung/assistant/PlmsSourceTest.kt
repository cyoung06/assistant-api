package com.syeyoung.assistant

import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.plms.PlmsSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PlmsSourceTest {
    private val mapper = JsonMapper.builder().build()
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `course names drop the term and section codes`() {
        assertThat(PlmsSource.courseName("확률및통계 (2026-092, 01400415_01660701)")).isEqualTo("확률및통계")
        // A merged section ends up with the same name as the plain one.
        assertThat(PlmsSource.courseName("고급 알고리즘 (2026-092, 01402548_01663255)-HAPBAN-CSMS-01402548-CSMS-01663255"))
            .isEqualTo("고급 알고리즘")
        assertThat(PlmsSource.courseName("Seminar")).isEqualTo("Seminar")
    }

    @Test
    fun `titles drop Moodle's 'is due' suffix`() {
        assertThat(PlmsSource.cleanTitle("HW6 is due")).isEqualTo("HW6")
        assertThat(PlmsSource.cleanTitle("Quiz 2 closes")).isEqualTo("Quiz 2")
        assertThat(PlmsSource.cleanTitle("(1~15분반 과제2) 지도교수 면담 보고서 is due")).isEqualTo("(1~15분반 과제2) 지도교수 면담 보고서")
    }

    @Test
    fun `an assignment event becomes a record due at the PLMS time, tagged with its course`() {
        // 2026-10-20 19:00 in Seoul.
        val event = mapper.readTree(
            """
            {"id": 9001, "name": "HW6 is due", "modulename": "assign", "instance": 4242,
             "timesort": 1792490400, "url": "https://plms.postech.ac.kr/mod/assign/view.php?id=77",
             "course": {"id": 15, "fullname": "확률및통계 (2026-092, 01400415_01660701)"}}
            """,
        )
        val record = PlmsSource.toRecord(event, seoul)
        assertThat(record.ref).isEqualTo("assign:4242")
        assertThat(record.title).isEqualTo("HW6")
        assertThat(record.dueDate).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(record.dueTime).isEqualTo(LocalTime.of(19, 0))
        assertThat(record.url).isEqualTo("https://plms.postech.ac.kr/mod/assign/view.php?id=77")
        assertThat(record.project).isEqualTo(SourceTag("course:15", "확률및통계"))
        assertThat(record.tags).containsExactly(SourceTag("module:assign", "assignment"))
    }

    @Test
    fun `midnight deadlines stay at 00 00 of the PLMS date`() {
        // 2026-10-21 00:00 in Seoul.
        val event = mapper.readTree("""{"id": 1, "name": "Homework 1 is due", "modulename": "assign", "instance": 5, "timesort": 1792508400}""")
        val record = PlmsSource.toRecord(event, seoul)
        assertThat(record.dueDate).isEqualTo(LocalDate.of(2026, 10, 21))
        assertThat(record.dueTime).isEqualTo(LocalTime.MIDNIGHT)
        assertThat(record.tags).containsExactly(SourceTag("module:assign", "assignment"))
    }

    @Test
    fun `an assignment's instructions and attached files come from its page`() {
        val html = """
            <div class="activity-header"><div class="activity-description" id="intro">
              <div class="box py-3 generalbox boxaligncenter"><div class="no-overflow"><p>Solve <b>all</b> problems.</p><p>See <a href="https://x/y">the notes</a>.</p></div></div>
              <div id="assign_files_tree68f1"><ul><li><div><a href="https://plms.postech.ac.kr/pluginfile.php/123/mod_assign/introattachment/0/HW%201.pdf?forcedownload=1">HW 1.pdf</a></div></li></ul></div>
            </div></div>
            <div class="submissionstatustable">Not graded</div>
        """.trimIndent()
        val a = com.syeyoung.assistant.source.plms.PlmsClient.parseAssignment(html)
        assertThat(a.attachments.map { it.name }).containsExactly("HW 1.pdf")
        assertThat(a.attachments.single().url).isEqualTo("https://plms.postech.ac.kr/pluginfile.php/123/mod_assign/introattachment/0/HW%201.pdf?forcedownload=1")
        assertThat(PlmsSource.htmlToMarkdown(a.introHtml)).isEqualTo("Solve all problems.\n\nSee [the notes](https://x/y).")
    }
}
