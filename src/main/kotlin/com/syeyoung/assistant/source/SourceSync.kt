package com.syeyoung.assistant.source

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.item.Item
import com.syeyoung.assistant.item.ItemFields
import com.syeyoung.assistant.item.ItemRepository
import com.syeyoung.assistant.item.ItemViews
import com.syeyoung.assistant.item.SourceType
import com.syeyoung.assistant.tag.TagRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies a source's snapshot to items, in one transaction.
 *
 * Ownership: the source owns an item's title, deadline and link, and updates them when they change
 * at the source. Everything else (done, tags, notes, scheduled time, sub-items) belongs to the user
 * and is never touched by a sync, except that an item the source stops reporting is marked done
 * when the snapshot asks for it. Items the user deleted are not recreated.
 */
@Component
class SourceApplier(
    private val items: ItemRepository,
    private val tags: TagRepository,
    private val sources: SourceRepository,
    private val props: AppProperties,
) {
    @Transactional
    suspend fun apply(type: SourceType, snapshot: SourceSnapshot, now: Instant): SyncReport {
        var report = SyncReport()
        val tagCache = mutableMapOf<String, UUID?>()

        for (record in snapshot.records) {
            val existing = sources.itemByRef(type, record.ref)
            report = when {
                existing == null -> {
                    val tagIds = record.tags.mapNotNull { t -> tagCache.getOrPut(t.key) { resolveTag(type, t, now) } }.distinct()
                    items.insert(UUID.randomUUID(), newFields(type, record, tagIds), now)
                    report.copy(created = report.created + 1)
                }
                existing.deletedAt != null -> report.copy(skippedDeleted = report.skippedDeleted + 1)
                else -> {
                    val updated = existing.fields.copy(
                        title = record.title,
                        byDate = record.dueDate,
                        byTime = record.dueTime,
                        sourceUrl = record.url,
                    )
                    if (updated == existing.fields) {
                        report.copy(unchanged = report.unchanged + 1)
                    } else {
                        items.update(existing.id, updated, now)
                        report.copy(updated = report.updated + 1)
                    }
                }
            }
        }

        snapshot.completeMissingDueFrom?.let { from ->
            val reported = snapshot.records.map { it.ref }.toSet()
            for (item in sources.openItems(type)) {
                if (item.fields.sourceRef in reported || !isDueFrom(item, from)) continue
                items.update(item.id, item.fields.copy(doneAt = now), now)
                items.completeDescendants(item.id, now, now)
                report = report.copy(completed = report.completed + 1)
            }
        }
        return report
    }

    private fun isDueFrom(item: Item, from: Instant): Boolean {
        val deadline = ItemViews.deadline(item.fields, props.zone) ?: return false
        return !deadline.isBefore(from)
    }

    private fun newFields(type: SourceType, record: SourceRecord, tagIds: List<UUID>) = ItemFields(
        title = record.title,
        byDate = record.dueDate,
        byTime = record.dueTime,
        mainTagId = tagIds.firstOrNull(),
        tagIds = tagIds,
        sourceType = type,
        sourceRef = record.ref,
        sourceUrl = record.url,
    )

    /**
     * The tag for an external key: the mapped tag, else an existing tag with the same name, else a new
     * one. Null when the mapped tag was deleted: the user removed it on purpose, so don't bring it back.
     */
    private suspend fun resolveTag(type: SourceType, tag: SourceTag, now: Instant): UUID? {
        sources.mappedTag(type, tag.key)?.let { mapped -> return tags.find(mapped)?.id }
        val id = tags.findAll().firstOrNull { it.name.equals(tag.name, ignoreCase = true) }?.id
            ?: tags.insert(UUID.randomUUID(), tag.name.take(60), leastUsedColor(), now).id
        sources.mapTag(type, tag.key, id)
        return id
    }

    /** The palette color fewest tags use, so a new course doesn't share a color with another. */
    private suspend fun leastUsedColor(): String {
        val counts = tags.findAll().groupingBy { it.color.uppercase() }.eachCount()
        return PALETTE.minBy { counts[it] ?: 0 }
    }

    private companion object {
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
                val report = applier.apply(source.type, snapshot, now)
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
