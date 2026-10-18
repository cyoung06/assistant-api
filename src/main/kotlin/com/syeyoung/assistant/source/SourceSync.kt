package com.syeyoung.assistant.source

import com.syeyoung.assistant.dav.Event
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.tasks.TaskSourceSync
import com.syeyoung.assistant.tasks.leastUsed
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
 * Applies a source's snapshot: records with a deadline go to Tasks ([TaskSourceSync]), records
 * with a time become events in the source's own calendar(s) — one calendar is one source.
 *
 * Event ownership: the source owns title, time, link (and notes, when it sends them); categories
 * and anything a CalDAV client added stay. Events the user deleted are not recreated.
 */
@Component
class SourceApplier(
    private val ws: Workspace,
    private val sources: SourceRepository,
    private val tasks: TaskSourceSync,
) {
    suspend fun apply(type: SourceType, displayName: String, snapshot: SourceSnapshot, now: Instant): SyncReport {
        val (eventRecords, taskRecords) = snapshot.records.partition { it.atStart != null || it.atStartDate != null }
        return applyEvents(type, displayName, eventRecords, snapshot) + tasks.apply(type, taskRecords, snapshot, now)
    }

    private suspend fun applyEvents(type: SourceType, displayName: String, records: List<SourceRecord>, snapshot: SourceSnapshot): SyncReport {
        var report = SyncReport()
        val prefix = "${type.wire}:"
        val events = ws.snapshot().events.filter { it.sourceRef?.startsWith(prefix) == true }.associateBy { it.sourceRef!! }
        val known = sources.refs(type)
        val calendarFor = mutableMapOf<String, String>()

        for (record in records) {
            val ref = prefix + record.ref
            val target = record.calendar ?: SourceTag(type.wire, displayName)
            val calendarId = calendarFor.getOrPut(target.key) { resolveCalendar(type, target) }
            val existing = events[ref]
            val fields = Workspace.EventFields(
                title = record.title, notes = record.notes ?: existing?.notes.orEmpty(),
                start = record.atStart, end = record.atEnd, startDate = record.atStartDate, endDate = record.atEndDate,
                categories = existing?.categories ?: record.tags.map { it.name }, url = record.url, sourceRef = ref,
            )
            report = when {
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
        }

        if (snapshot.deleteMissing) {
            val reported = records.map { prefix + it.ref }.toSet()
            for (o in events.values.filter { it.sourceRef !in reported }) { ws.delete(o.href, o.etag); report = report.copy(deleted = report.deleted + 1) }
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
        val cal = s.calendars.find { it.id == slug } ?: ws.createCalendar(target.name, leastUsed(s.calendars.mapNotNull { it.color }), slug = slug)
        sources.mapCalendar(type, target.key, cal.id)
        return cal.id
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
