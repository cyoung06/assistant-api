package com.syeyoung.assistant

import com.syeyoung.assistant.ical.IcalEvent
import com.syeyoung.assistant.ical.IcalParser
import com.syeyoung.assistant.ical.IcalWriter
import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.ical.IcalSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IcalTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `writer escapes text, folds long lines at 75 octets, and round-trips through the parser`() {
        val long = "고급 알고리즘; 과제, 그리고 매우 긴 설명이 계속 이어집니다 ".repeat(4).trim()
        val text = IcalWriter.write(
            "Assistant",
            listOf(
                IcalEvent("a@x", long, description = "line1\nline2", start = Instant.parse("2026-10-20T03:30:00Z"), end = Instant.parse("2026-10-20T04:45:00Z")),
                IcalEvent("b@x", "Holiday", startDate = LocalDate.parse("2026-10-09"), endDate = LocalDate.parse("2026-10-10")),
            ),
        )
        assertThat(text.split("\r\n").filter { it.isNotEmpty() }).allSatisfy { assertThat(it.toByteArray().size).isLessThanOrEqualTo(75) }
        assertThat(text).contains("DTSTART:20261020T033000Z").contains("DTSTART;VALUE=DATE:20261009").contains("\\;").contains("\\n")

        val back = IcalParser.parse(text, seoul)
        assertThat(back.map { it.summary }).containsExactly(long, "Holiday")
        assertThat(back[0].description).isEqualTo("line1\nline2")
        assertThat(back[0].start).isEqualTo(Instant.parse("2026-10-20T03:30:00Z"))
        assertThat(back[1].startDate).isEqualTo(LocalDate.parse("2026-10-09"))
    }

    @Test
    fun `parser handles TZID, floating times, alarms and junk`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:1
            DTSTART;TZID=America/New_York:20261020T090000
            DTEND;TZID=America/New_York:20261020T100000
            SUMMARY:NY meeting
            BEGIN:VALARM
            TRIGGER:-PT15M
            SUMMARY:alarm text
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:2
            DTSTART:20261020T090000
            SUMMARY:Floating
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:No UID, skipped
            DTSTART:20261020T090000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = IcalParser.parse(ics, seoul)
        assertThat(events.map { it.summary }).containsExactly("NY meeting", "Floating")
        assertThat(events[0].start).isEqualTo(Instant.parse("2026-10-20T13:00:00Z"))
        assertThat(events[1].start).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"))
    }

    @Test
    fun `holiday events become all-day records with inclusive ends`() {
        val e = IcalEvent("hangul@google", "한글날", startDate = LocalDate.parse("2026-10-09"), endDate = LocalDate.parse("2026-10-10"))
        val r = IcalSource.toRecords("kr", e, SourceTag("ical:kr", "holiday"), LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31"), seoul).single()
        assertThat(r.ref).isEqualTo("kr:hangul@google")
        assertThat(r.atStartDate).isEqualTo(LocalDate.parse("2026-10-09"))
        assertThat(r.atEndDate).isEqualTo(LocalDate.parse("2026-10-09"))
    }

    @Test
    fun `repeating feed events expand within the window, skipping EXDATEs`() {
        val e = IcalEvent(
            "seminar@x", "Seminar",
            start = Instant.parse("2026-10-07T07:00:00Z"), end = Instant.parse("2026-10-07T08:00:00Z"),
            rrule = "FREQ=WEEKLY;BYDAY=WE;COUNT=4", exdates = listOf(LocalDate.parse("2026-10-14")),
        )
        val records = IcalSource.toRecords("x", e, SourceTag("ical:x", "seminar"), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), seoul)
        assertThat(records.map { it.ref }).containsExactly("x:seminar@x#2026-10-07", "x:seminar@x#2026-10-21", "x:seminar@x#2026-10-28")
        assertThat(records[1].atStart).isEqualTo(Instant.parse("2026-10-21T07:00:00Z"))
        // Unsupported rules keep just the first occurrence.
        assertThat(IcalSource.parseRule("FREQ=MONTHLY;BYDAY=1MO", seoul)).isNull()
        assertThat(IcalSource.parseRule("FREQ=YEARLY", seoul)!!.interval).isEqualTo(12)
    }
}
