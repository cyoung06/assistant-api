package com.syeyoung.assistant

import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.series.Freq
import com.syeyoung.assistant.series.Recurrence
import com.syeyoung.assistant.series.RepeatRule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate

class RecurrenceTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val far = d("2030-01-01")

    @Test
    fun `weekly on two days until a date`() {
        // A Tue/Thu class from Tue Sep 8 to Thu Sep 24.
        val rule = RepeatRule(Freq.WEEKLY, weekdays = setOf(TUESDAY, THURSDAY), until = d("2026-09-24"))
        assertThat(Recurrence.dates(rule, d("2026-09-08"), d("2026-09-01"), far).map(LocalDate::toString))
            .containsExactly("2026-09-08", "2026-09-10", "2026-09-15", "2026-09-17", "2026-09-22", "2026-09-24")
    }

    @Test
    fun `weekly starts on the start date even mid-week`() {
        // Starting on a Thursday: that week's Tuesday is skipped.
        val rule = RepeatRule(Freq.WEEKLY, weekdays = setOf(TUESDAY, THURSDAY), count = 3)
        assertThat(Recurrence.dates(rule, d("2026-09-10"), d("2026-09-01"), far).map(LocalDate::toString))
            .containsExactly("2026-09-10", "2026-09-15", "2026-09-17")
    }

    @Test
    fun `every other week, defaulting to the start date's weekday`() {
        val rule = RepeatRule(Freq.WEEKLY, interval = 2, count = 3)
        assertThat(Recurrence.dates(rule, d("2026-10-22"), d("2026-10-01"), far).map(LocalDate::toString))
            .containsExactly("2026-10-22", "2026-11-05", "2026-11-19")
    }

    @Test
    fun `count includes occurrences before the window`() {
        val rule = RepeatRule(Freq.DAILY, count = 5)
        assertThat(Recurrence.dates(rule, d("2026-10-01"), d("2026-10-04"), far).map(LocalDate::toString))
            .containsExactly("2026-10-04", "2026-10-05")
    }

    @Test
    fun `monthly on the 31st skips short months`() {
        val rule = RepeatRule(Freq.MONTHLY, count = 3)
        assertThat(Recurrence.dates(rule, d("2026-10-31"), d("2026-10-01"), far).map(LocalDate::toString))
            .containsExactly("2026-10-31", "2026-12-31", "2027-01-31")
    }

    @Test
    fun `window end limits an endless series`() {
        val rule = RepeatRule(Freq.DAILY, interval = 3)
        assertThat(Recurrence.dates(rule, d("2026-10-01"), d("2026-10-01"), d("2026-10-10"))).hasSize(4)
    }

    @Test
    fun `rejects contradictory rules`() {
        assertThatThrownBy { RepeatRule(Freq.DAILY, until = d("2026-12-01"), count = 3).validate(d("2026-10-01")) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RepeatRule(Freq.DAILY, weekdays = setOf(TUESDAY)).validate(d("2026-10-01")) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RepeatRule(Freq.WEEKLY, until = d("2026-09-01")).validate(d("2026-10-01")) }
            .isInstanceOf(ValidationException::class.java)
    }
}
