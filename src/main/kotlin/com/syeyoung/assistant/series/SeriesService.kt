package com.syeyoung.assistant.series

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.VersionMismatchException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.item.Item
import com.syeyoung.assistant.item.ItemFields
import com.syeyoung.assistant.item.ItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Repeating events. Occurrences are generated as real items up to a rolling horizon. Series-wide
 * edits update the template and every occurrence that hasn't been changed on its own ("detached");
 * deleted occurrences are never regenerated.
 */
@Service
class SeriesService(
    private val items: ItemRepository,
    private val series: SeriesRepository,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val zone get() = props.zone

    suspend fun get(id: UUID): Series = series.find(id) ?: throw NotFoundException("Series", id)

    /** Makes [item] the first occurrence of a new series repeating by [rule], and generates the rest. */
    @Transactional
    suspend fun start(item: Item, rule: RepeatRule): Item {
        if (item.fields.seriesId != null) throw ConflictException("already_repeats", "This item already repeats")
        val (template, start) = templateFrom(item.fields)
        rule.validate(start)
        if (Recurrence.dates(rule, start, start, start).isEmpty()) {
            throw ValidationException("The item's date must be one of the days it repeats on")
        }
        val now = Instant.now(clock)
        val created = series.insert(
            Series(UUID.randomUUID(), template.copy(notes = item.fields.notes), rule, start, null, 1, 0, now, now, null),
            now,
        )
        items.update(item.id, item.fields.copy(seriesId = created.id, occurrenceDate = start, detached = false), now)
        generate(created, now)
        return items.find(item.id)!!
    }

    /**
     * Makes sure every rule date up to the horizon has an occurrence. An occurrence the user deleted on
     * its own (an exception, e.g. a holiday) stays deleted; one removed by ending the series early or
     * changing its rule comes back if the rule includes its date again.
     */
    suspend fun generate(s: Series, now: Instant): Series {
        val horizon = LocalDate.now(clock).plusDays(HORIZON_DAYS)
        val through = s.rule.until?.let { minOf(it, horizon) } ?: horizon
        val from = s.generatedUntil?.plusDays(1) ?: s.startDate
        if (from > through) return s
        val existing = series.occurrences(s.id)
        for (date in Recurrence.dates(s.rule, s.startDate, from, through)) {
            val item = existing[date]
            when {
                item == null -> {
                    val fields = applyTemplate(ItemFields(notes = s.template.notes, seriesId = s.id, occurrenceDate = date), s.template, date)
                    items.insert(UUID.randomUUID(), fields, now)
                }
                item.deletedAt != null && !item.fields.detached -> {
                    items.restoreTree(item.id, item.deletedAt, now)
                    items.update(item.id, applyTemplate(item.fields, s.template, date), now)
                }
            }
        }
        return series.update(s.copy(generatedUntil = through), now)
    }

    /** Keeps every series generated up to the horizon as days pass. */
    @Transactional
    suspend fun extendAll(): Int {
        val now = Instant.now(clock)
        val due = series.needingGenerationThrough(LocalDate.now(clock).plusDays(HORIZON_DAYS))
        due.forEach { generate(it, now) }
        return due.size
    }

    /**
     * Applies an edit of one occurrence. [updated] is the occurrence after the edit. For
     * [Scope.FOLLOWING] and [Scope.ALL], its title, tags and time of day become the template for
     * the other occurrences in scope; its notes and done state stay its own.
     */
    @Transactional
    suspend fun edit(current: Item, updated: ItemFields, scope: Scope): Item {
        val now = Instant.now(clock)
        if (scope == Scope.THIS) {
            val detached = current.fields.detached || templateChanged(current.fields, updated)
            items.update(current.id, updated.copy(detached = detached), now)
            return items.find(current.id)!!
        }
        val occurrence = current.fields.occurrenceDate!!
        var s = series.find(current.fields.seriesId!!, forUpdate = true) ?: throw NotFoundException("Series", current.fields.seriesId)
        if (updated.byDate != null) throw ValidationException("Repeating events can't have a deadline")
        val (shape, date) = try {
            templateFrom(updated)
        } catch (e: ValidationException) {
            throw ValidationException("Every occurrence needs a time. To stop repeating, delete the following occurrences")
        }
        if (date != occurrence) {
            throw ValidationException("Moving to another day only works for this occurrence; change the repeat days to move them all")
        }
        val template = shape.copy(notes = s.template.notes, title = updated.title, mainTagId = updated.mainTagId, tagIds = updated.tagIds)

        if (scope == Scope.FOLLOWING && occurrence > s.startDate) s = split(s, occurrence, now)
        s = series.update(s.copy(template = template), now)
        for (item in series.liveItems(s.id)) {
            if (item.id == current.id || item.fields.detached) continue
            items.update(item.id, applyTemplate(item.fields, template, item.fields.occurrenceDate!!), now)
        }
        items.update(current.id, updated.copy(seriesId = s.id, detached = false), now)
        return items.find(current.id)!!
    }

    /** Deletes one occurrence, it and the following ones, or the whole series. */
    @Transactional
    suspend fun delete(current: Item, scope: Scope) {
        val now = Instant.now(clock)
        val occurrence = current.fields.occurrenceDate!!
        val s = series.find(current.fields.seriesId!!, forUpdate = true)
        when {
            scope == Scope.THIS || s == null -> {
                // Marked as an exception, so regenerating the series never brings it back.
                items.update(current.id, current.fields.copy(detached = true), now)
                items.softDeleteTree(current.id, now)
            }
            scope == Scope.ALL || occurrence <= s.startDate -> {
                series.liveItems(s.id).forEach { items.softDeleteTree(it.id, now) }
                series.softDelete(s.id, now)
            }
            else -> {
                series.update(endBefore(s, occurrence), now)
                series.liveItems(s.id, from = occurrence).forEach { items.softDeleteTree(it.id, now) }
            }
        }
    }

    /**
     * Changes how a series repeats. Future occurrences that no longer fit the rule are deleted
     * unless they were changed on their own or are done; missing ones are generated.
     */
    @Transactional
    suspend fun changeRule(id: UUID, rule: RepeatRule, expectedVersion: Long?): Series {
        val s = series.find(id, forUpdate = true) ?: throw NotFoundException("Series", id)
        if (expectedVersion != null && expectedVersion != s.version) throw VersionMismatchException(s.version)
        rule.validate(s.startDate)
        if (Recurrence.dates(rule, s.startDate, s.startDate, s.startDate).isEmpty()) {
            throw ValidationException("The first occurrence must be one of the days it repeats on")
        }
        val now = Instant.now(clock)
        val today = LocalDate.now(clock)
        val horizon = today.plusDays(HORIZON_DAYS)
        val keep = Recurrence.dates(rule, s.startDate, today, horizon).toSet()
        for (item in series.liveItems(id, from = today)) {
            val date = item.fields.occurrenceDate!!
            if (date !in keep && !item.fields.detached && item.fields.doneAt == null) items.softDeleteTree(item.id, now)
        }
        val regenerateFrom = today.minusDays(1)
        val updated = series.update(s.copy(rule = rule, generatedUntil = s.generatedUntil?.let { minOf(it, regenerateFrom) }), now)
        return generate(updated, now)
    }

    /** Ends [s] the day before [occurrence] and moves that and later occurrences to a new series. */
    private suspend fun split(s: Series, occurrence: LocalDate, now: Instant): Series {
        series.update(endBefore(s, occurrence), now)
        val before = Recurrence.dates(s.rule, s.startDate, s.startDate, occurrence.minusDays(1)).size
        val rest = s.copy(
            id = UUID.randomUUID(),
            rule = s.rule.copy(count = s.rule.count?.let { it - before }),
            startDate = occurrence,
        )
        val created = series.insert(rest, now)
        series.moveItems(s.id, created.id, occurrence, now)
        return created
    }

    /** [s] with its rule ending the day before [occurrence]. */
    private fun endBefore(s: Series, occurrence: LocalDate): Series {
        val lastDay = occurrence.minusDays(1)
        val rule = if (s.rule.count != null) {
            s.rule.copy(count = Recurrence.dates(s.rule, s.startDate, s.startDate, lastDay).size)
        } else {
            s.rule.copy(until = lastDay)
        }
        return s.copy(rule = rule, generatedUntil = s.generatedUntil?.let { minOf(it, lastDay) })
    }

    /** The template shape (time of day and length) and date of an item that has a scheduled time. */
    fun templateFrom(f: ItemFields): Pair<SeriesTemplate, LocalDate> {
        if (f.byDate != null) throw ValidationException("Repeating events can't have a deadline")
        if (f.parentId != null) throw ValidationException("Sub-items can't repeat")
        val base = SeriesTemplate(f.title, f.notes, f.mainTagId, f.tagIds, allDay = false)
        return when {
            f.atStart != null && f.atEnd != null -> {
                val start = f.atStart.atZone(zone)
                base.copy(startTime = start.toLocalTime(), durationMinutes = Duration.between(f.atStart, f.atEnd).toMinutes().toInt()) to
                    start.toLocalDate()
            }
            f.atStartDate != null && f.atEndDate != null ->
                base.copy(allDay = true, spanDays = ChronoUnit.DAYS.between(f.atStartDate, f.atEndDate).toInt() + 1) to f.atStartDate
            else -> throw ValidationException("Only items with a scheduled time can repeat")
        }
    }

    /** [fields] with the template's title, tags and time placed on [date]. Notes and done stay. */
    private fun applyTemplate(fields: ItemFields, t: SeriesTemplate, date: LocalDate): ItemFields {
        val base = fields.copy(title = t.title, mainTagId = t.mainTagId, tagIds = t.tagIds, byDate = null, byTime = null)
        return if (t.allDay) {
            base.copy(atStart = null, atEnd = null, atStartDate = date, atEndDate = date.plusDays(t.spanDays!!.toLong() - 1))
        } else {
            val start = date.atTime(t.startTime).atZone(zone).toInstant()
            base.copy(atStart = start, atEnd = start.plusSeconds(t.durationMinutes!! * 60L), atStartDate = null, atEndDate = null)
        }
    }

    private fun templateChanged(old: ItemFields, new: ItemFields) =
        old.title != new.title || old.mainTagId != new.mainTagId || old.tagIds.toSet() != new.tagIds.toSet() ||
            old.atStart != new.atStart || old.atEnd != new.atEnd || old.atStartDate != new.atStartDate || old.atEndDate != new.atEndDate

    companion object {
        /** Occurrences exist as items this many days ahead. */
        const val HORIZON_DAYS = 120L
    }
}
