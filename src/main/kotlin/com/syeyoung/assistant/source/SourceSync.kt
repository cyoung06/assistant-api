package com.syeyoung.assistant.source

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.Cal
import com.syeyoung.assistant.dav.Due
import com.syeyoung.assistant.dav.Event
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.source.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies a source's snapshot to the CalDAV workspace.
 *
 * Every source writes only into its own calendar(s) — one calendar is one source — so whether
 * something is the user's or a source's follows from where it lives. Tags become CATEGORIES.
 * Ownership within a source's items: the source owns title, deadline or time, link (and notes, when
 * it sends them); done, extra categories, sub-tasks and anything a client added stay the user's.
 * Objects the user deleted are not recreated.
 */
@Component
class SourceApplier(
    private val ws: Workspace,
    private val sources: SourceRepository,
    private val props: AppProperties,
) {
    suspend fun apply(type: SourceType, displayName: String, snapshot: SourceSnapshot, now: Instant): SyncReport {
        var report = SyncReport()
        val prefix = "${type.wire}:"
        val s = ws.snapshot()
        val tasks = s.tasks.filter { it.sourceRef?.startsWith(prefix) == true }.associateBy { it.sourceRef!! }
        val events = s.events.filter { it.sourceRef?.startsWith(prefix) == true }.associateBy { it.sourceRef!! }
        val known = sources.refs(type)
        val calendarFor = mutableMapOf<String, String>()

        for (record in snapshot.records) {
            val ref = prefix + record.ref
            val target = record.calendar ?: SourceTag(type.wire, displayName)
            val calendarId = calendarFor.getOrPut(target.key) { resolveCalendar(type, target) }
            val categories = record.tags.map { it.name }
            val isEvent = record.atStart != null || record.atStartDate != null
            report = if (isEvent) {
                val existing = events[ref]
                val fields = Workspace.EventFields(
                    title = record.title, notes = record.notes ?: existing?.notes.orEmpty(),
                    start = record.atStart, end = record.atEnd, startDate = record.atStartDate, endDate = record.atEndDate,
                    categories = existing?.categories ?: categories, relatedUid = existing?.relatedUid, url = record.url, sourceRef = ref,
                )
                when {
                    existing == null && ref in known -> report.copy(skippedDeleted = report.skippedDeleted + 1)
                    existing == null -> {
                        val e = ws.createEvent(calendarId, fields)
                        sources.rememberRef(type, ref, e.uid)
                        report.copy(created = report.created + 1)
                    }
                    sameEvent(existing, fields) -> report.copy(unchanged = report.unchanged + 1)
                    else -> {
                        ws.updateEvent(existing) { it.copy(title = fields.title, notes = fields.notes, start = fields.start, end = fields.end, startDate = fields.startDate, endDate = fields.endDate, url = fields.url) }
                        report.copy(updated = report.updated + 1)
                    }
                }
            } else {
                val existing = tasks[ref]
                val due = record.dueDate?.let { Due(it, record.dueTime) }
                when {
                    existing == null && ref in known -> report.copy(skippedDeleted = report.skippedDeleted + 1)
                    existing == null -> {
                        val t = ws.createTask(calendarId, Workspace.TaskFields(record.title, record.notes.orEmpty(), due, categories = categories, url = record.url, sourceRef = ref))
                        sources.rememberRef(type, ref, t.uid)
                        report.copy(created = report.created + 1)
                    }
                    existing.title == record.title && existing.due == due && existing.url == record.url && (record.notes == null || existing.notes == record.notes) ->
                        report.copy(unchanged = report.unchanged + 1)
                    else -> {
                        ws.updateTask(existing) { it.copy(title = record.title, due = due, url = record.url, notes = record.notes ?: it.notes) }
                        report.copy(updated = report.updated + 1)
                    }
                }
            }
        }

        val reported = snapshot.records.map { prefix + it.ref }.toSet()
        if (snapshot.deleteMissing) {
            for (o in events.values.filter { it.sourceRef !in reported }) { ws.delete(o.href, o.etag); report = report.copy(deleted = report.deleted + 1) }
            for (o in tasks.values.filter { it.sourceRef !in reported }) { ws.delete(o.href, o.etag); report = report.copy(deleted = report.deleted + 1) }
        }
        snapshot.completeMissingDueFrom?.let { from ->
            for (t in tasks.values) {
                if (t.sourceRef in reported || t.done) continue
                val deadline = t.due?.let { d -> d.time?.let { d.date.atTime(it).atZone(props.zone).toInstant() } ?: d.date.plusDays(1).atStartOfDay(props.zone).toInstant() }
                if (deadline == null || deadline.isBefore(from)) continue
                ws.updateTask(t) { it.copy(done = true) }
                report = report.copy(completed = report.completed + 1)
            }
        }
        return report
    }

    private fun sameEvent(e: Event, f: Workspace.EventFields) =
        e.title == f.title && e.notes == f.notes && e.start == f.start && e.end == f.end && e.startDate == f.startDate && e.endDate == f.endDate && e.url == f.url

    /** The source's calendar for [target]: the remembered one, or a new one (also when it was deleted). */
    private suspend fun resolveCalendar(type: SourceType, target: SourceTag): String {
        val s = ws.snapshot()
        sources.mappedCalendar(type, target.key)?.let { mapped -> s.calendars.find { it.id == mapped }?.let { return it.id } }
        val slug = "src-" + target.key.replace(Regex("[^A-Za-z0-9-]"), "-")
        val cal = s.calendars.find { it.id == slug } ?: ws.createCalendar(target.name, leastUsedColor(s.calendars), slug = slug)
        sources.mapCalendar(type, target.key, cal.id)
        return cal.id
    }

    private fun leastUsedColor(calendars: List<Cal>): String {
        val counts = calendars.groupingBy { it.color?.uppercase() }.eachCount()
        return PALETTE.minBy { counts[it] ?: 0 }
    }

    companion object {
        val PALETTE = listOf("#2F5BE0", "#E0642F", "#1F8A4C", "#A23FD1", "#D13B6E", "#0E8C9C", "#B88A00", "#7A5230", "#5B6CFF", "#C2410C", "#4D7C0F", "#BE185D")
    }
}

data class SourceStatus(
    val type: SourceType,
    val displayName: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val syncing: Boolean,
    val lastSyncAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    val lastReport: SyncReport?,
)

/** Runs syncs: one at a time per source, recording the outcome. */
@Service
class SourceSync(
    private val dataSources: List<DataSource>,
    private val applier: SourceApplier,
    private val repository: SourceRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<SourceType, Mutex>()

    fun all(): List<DataSource> = dataSources

    fun find(type: SourceType): DataSource? = dataSources.find { it.type == type }

    suspend fun status(source: DataSource): SourceStatus {
        val row = repository.status(source.type)
        return SourceStatus(
            type = source.type,
            displayName = source.displayName,
            enabled = source.enabled,
            intervalMinutes = source.interval.toMinutes(),
            syncing = lockFor(source.type).isLocked,
            lastSyncAt = row?.lastSyncAt,
            lastSuccessAt = row?.lastSuccessAt,
            lastError = row?.lastError,
            lastReport = row?.lastReport,
        )
    }

    /**
     * Fetches from [source] and applies the result. A sync already running for the same source is
     * waited for rather than duplicated. Failures are recorded, not thrown.
     */
    suspend fun run(source: DataSource): SourceStatus {
        lockFor(source.type).withLock {
            val now = Instant.now(clock)
            try {
                val snapshot = source.fetch(now)
                val report = applier.apply(source.type, source.displayName, snapshot, now)
                repository.recordSuccess(source.type, now, report)
                log.info("Synced {}: {}", source.type.wire, report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Sync of {} failed: {}", source.type.wire, e.toString())
                repository.recordFailure(source.type, now, e.message ?: e.javaClass.simpleName)
            }
        }
        return status(source)
    }

    private fun lockFor(type: SourceType) = locks.computeIfAbsent(type) { Mutex() }
}
