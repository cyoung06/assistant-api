package com.syeyoung.assistant.ical

import com.fasterxml.jackson.annotation.JsonValue
import com.syeyoung.assistant.common.ValidationException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class Freq(@get:JsonValue val wire: String) {
    DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly");

    companion object {
        fun fromWire(value: String) = entries.find { it.wire == value } ?: throw IllegalArgumentException("unknown freq $value")
    }
}

/**
 * When a series repeats: a subset of iCalendar's RRULE (FREQ, INTERVAL, BYDAY for weekly, UNTIL,
 * COUNT). Monthly repeats on the start date's day of the month and skips months without that day.
 */
data class RepeatRule(
    val freq: Freq,
    val interval: Int = 1,
    /** Weekly only. Empty means the weekday of the start date. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** Last possible occurrence date, inclusive. */
    val until: LocalDate? = null,
    /** Total number of occurrences, counted from the start. */
    val count: Int? = null,
) {
    fun validate(start: LocalDate) {
        if (interval !in 1..99) throw ValidationException("Repeat interval must be between 1 and 99")
        if (until != null && count != null) throw ValidationException("Repeat until a date or a number of times, not both")
        if (until != null && until < start) throw ValidationException("Repeat end date is before the first occurrence")
        if (count != null && count !in 1..1000) throw ValidationException("Repeat count must be between 1 and 1000")
        if (freq != Freq.WEEKLY && weekdays.isNotEmpty()) throw ValidationException("Weekdays only apply to weekly repeats")
    }
}

object Recurrence {
    private const val MAX_STEPS = 20_000

    /**
     * Occurrence dates of [rule] for a series starting on [start], in order, limited to [from]..[to]
     * (inclusive). The count, if any, includes occurrences before [from].
     */
    fun dates(rule: RepeatRule, start: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val last = listOfNotNull(to, rule.until).min()
        val result = mutableListOf<LocalDate>()
        var produced = 0
        for (date in candidates(rule, start)) {
            if (date > last) break
            if (rule.count != null && produced >= rule.count) break
            produced++
            if (date >= from) result += date
        }
        return result
    }

    /** All dates the rule produces from [start] on, in order (bounded to keep runaway rules finite). */
    private fun candidates(rule: RepeatRule, start: LocalDate): Sequence<LocalDate> = sequence {
        when (rule.freq) {
            Freq.DAILY -> for (k in 0 until MAX_STEPS) yield(start.plusDays(k.toLong() * rule.interval))
            Freq.WEEKLY -> {
                val days = rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }
                // Weeks start on Sunday, as in the app's calendar.
                val firstWeek = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                for (k in 0 until MAX_STEPS) {
                    val week = firstWeek.plusWeeks(k.toLong() * rule.interval)
                    for (offset in 0L..6L) {
                        val date = week.plusDays(offset)
                        if (date.dayOfWeek in days && date >= start) yield(date)
                    }
                }
            }
            Freq.MONTHLY -> {
                val first = YearMonth.from(start)
                for (k in 0 until MAX_STEPS) {
                    val month = first.plusMonths(k.toLong() * rule.interval)
                    if (start.dayOfMonth <= month.lengthOfMonth()) yield(month.atDay(start.dayOfMonth))
                }
            }
        }
    }
}
