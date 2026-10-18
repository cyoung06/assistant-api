package com.syeyoung.assistant.source

import com.syeyoung.assistant.item.SourceType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * A plugin that pulls items from an external system. Plugins only fetch; [SourceSync] turns what
 * they return into items, so every source follows the same ownership rules.
 */
interface DataSource {
    val type: SourceType
    /** Shown in the app, e.g. "PLMS". */
    val displayName: String
    val enabled: Boolean
    val interval: Duration

    suspend fun fetch(now: Instant): SourceSnapshot
}

/** Everything a source currently reports, within the window it covers. */
data class SourceSnapshot(
    val records: List<SourceRecord>,
    /**
     * Items from this source that are no longer reported and are due at or after this instant are
     * marked done (e.g. an assignment disappears from PLMS's to-do list once submitted).
     * Null: never complete missing items.
     */
    val completeMissingDueFrom: Instant? = null,
    /**
     * Items from this source that are no longer reported are deleted (for calendars, where a missing
     * event was removed rather than completed).
     */
    val deleteMissing: Boolean = false,
)

/** One external thing that should exist as an item. */
data class SourceRecord(
    /** Stable ID within the source, e.g. "assign:1234". */
    val ref: String,
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    /** Scheduled time, for events: timed ([atStart]/[atEnd]) or all-day ([atStartDate]..[atEndDate], inclusive). */
    val atStart: Instant? = null,
    val atEnd: Instant? = null,
    val atStartDate: LocalDate? = null,
    val atEndDate: LocalDate? = null,
    val notes: String? = null,
    val url: String? = null,
    /** Tags to attach when the item is created; the first one becomes the main tag. */
    val tags: List<SourceTag> = emptyList(),
)

/** A tag the source wants, identified by the source's own key (e.g. a course ID). */
data class SourceTag(val key: String, val name: String)

data class SyncReport(
    val created: Int = 0,
    val updated: Int = 0,
    val completed: Int = 0,
    val unchanged: Int = 0,
    /** Records whose item was deleted in the app; they are not recreated. */
    val skippedDeleted: Int = 0,
    val deleted: Int = 0,
)
