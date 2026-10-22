package com.syeyoung.assistant.drive

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.core.noteLinks
import com.syeyoung.assistant.refs.AppLinks
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** A file or folder as the index has it. */
data class Indexed(val path: String, val name: String, val folder: Boolean, val modified: Instant?)

/**
 * The drive as last walked: every file and folder under Projects/ (to find one by name), and what
 * each note links to (for backlinks: a task lists the notes that link to it, a file the notes that
 * link to it). Walked every few minutes; notes edited live are read again as soon as they're saved.
 */
@Component
class DriveIndex(private val dav: WebDav, private val db: DatabaseClient, private val props: AppProperties, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    /** When notes saved live were last looked for. */
    private var since: Instant = Instant.EPOCH

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.drive.enabled) return
        scope.launch {
            var walked = Instant.EPOCH
            while (isActive) {
                try {
                    val now = Instant.now(clock)
                    if (now.isAfter(walked.plus(WALK_EVERY))) {
                        walk()
                        walked = now
                    } else {
                        notesSaved()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Drive index: {}", e.toString())
                }
                delay(30_000)
            }
        }
    }

    /**
     * Walks the drive: what's new or changed is kept (a note's links read again when its version
     * changed), what's gone is let go.
     */
    suspend fun walk(root: String = "${DriveService.PROJECTS}/") = lock.withLock {
        val known = db.sql("select path, etag from drive_index where path like :under").bind("under", root.replace("%", "\\%").replace("_", "\\_") + "%").map { r -> r.get("path", String::class.java)!! to r.get("etag", String::class.java) }.flow().toList().toMap()
        val seen = HashSet<String>()
        val folders = ArrayDeque(listOf(root))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            for (e in dav.list(folder).orEmpty()) {
                seen += e.path
                if (e.folder) folders += e.path
                // Unchanged since last walked.
                if (known.containsKey(e.path) && known[e.path] == e.etag) continue
                put(e)
                if (isNote(e)) readLinks(e.path)
            }
        }
        for (gone in known.keys - seen) forget(gone)
        since = Instant.now(clock)
    }

    /** Notes saved by live editing since last looked: their links read again. */
    internal suspend fun notesSaved() = lock.withLock {
        val now = Instant.now(clock)
        val saved = db.sql("select path from sync_docs where updated_at > :since and lower(path) like '%.md'").bind("since", since)
            .map { r -> r.get("path", String::class.java)!! }.flow().toList()
        since = now
        for (path in saved) {
            // A note made since the drive was last walked: known now (the next walk fills in the rest).
            if (get(path) == null) put(DriveEntry(path, path.substringAfterLast('/'), false, 0, now, "text/markdown", null))
            readLinks(path)
        }
    }

    private suspend fun put(e: DriveEntry) {
        var q = db.sql(
            """
            insert into drive_index (path, name, folder, modified, etag) values (:path, :name, :folder, :modified, :etag)
            on conflict (path) do update set name = excluded.name, folder = excluded.folder, modified = excluded.modified, etag = excluded.etag
            """.trimIndent(),
        ).bind("path", e.path).bind("name", e.name).bind("folder", e.folder)
        q = e.modified?.let { q.bind("modified", it) } ?: q.bindNull("modified", Instant::class.java)
        q = e.etag?.let { q.bind("etag", it) } ?: q.bindNull("etag", String::class.java)
        q.fetch().awaitRowsUpdated()
    }

    private suspend fun forget(path: String) {
        db.sql("delete from drive_index where path = :p").bind("p", path).fetch().awaitRowsUpdated()
        db.sql("delete from note_links where note = :p").bind("p", path).fetch().awaitRowsUpdated()
    }

    /** What the note at [path] links to now, kept (normalized: see [target]). */
    private suspend fun readLinks(path: String) {
        val r = dav.get(path) ?: return forget(path)
        val joined = DataBufferUtils.join(r.body ?: return).awaitSingleOrNull()
        val text = joined?.let { b -> ByteArray(b.readableByteCount()).also { b.read(it); DataBufferUtils.release(b) } }?.decodeToString().orEmpty()
        val folder = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val targets = noteLinks(text, folder).map(::target).distinct()
        db.sql("delete from note_links where note = :p").bind("p", path).fetch().awaitRowsUpdated()
        for (t in targets) db.sql("insert into note_links (note, target) values (:note, :target) on conflict do nothing").bind("note", path).bind("target", t).fetch().awaitRowsUpdated()
    }

    /** The notes linking to [target] (a link as written: see [target]), with what the index has of them. */
    suspend fun notesLinkingTo(target: String): List<Indexed> =
        db.sql(
            """
            select i.* from note_links l join drive_index i on i.path = l.note
            where l.target = :t and i.path not like '.Trash/%' order by i.modified desc nulls last
            """.trimIndent(),
        ).bind("t", target(target)).map(::row).flow().toList()

    /** Files and notes whose name has [query] in it, newest first. */
    suspend fun search(query: String, limit: Int): List<Indexed> =
        db.sql("select * from drive_index where not folder and name ilike :q order by (lower(name) like :start) desc, modified desc nulls last limit :limit")
            .bind("q", "%" + escape(query.trim()) + "%").bind("start", escape(query.trim().lowercase()) + "%").bind("limit", limit)
            .map(::row).flow().toList()

    /** Files and notes called [name] (a note also without its .md), whatever the case. */
    suspend fun byName(name: String): List<Indexed> =
        db.sql("select * from drive_index where not folder and path not like '.Trash/%' and (lower(name) = lower(:n) or lower(name) = lower(:n) || '.md') order by modified desc nulls last")
            .bind("n", name).map(::row).flow().toList()

    /** What the index has of [path]. */
    suspend fun get(path: String): Indexed? =
        db.sql("select * from drive_index where path = :p").bind("p", path).map(::row).flow().toList().firstOrNull()

    private fun row(r: io.r2dbc.spi.Readable) =
        Indexed(r.get("path", String::class.java)!!, r.get("name", String::class.java)!!, r.get("folder", java.lang.Boolean::class.java)!!.booleanValue(), r.get("modified", Instant::class.java))

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        private val WALK_EVERY = java.time.Duration.ofMinutes(10)

        private fun isNote(e: DriveEntry) = !e.folder && Regex("""\.(md|markdown)$""", RegexOption.IGNORE_CASE).containsMatchIn(e.name)

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * A link as it's matched: a drive file as `drive:<path>` (also when written as the app's
         * page of it), the app's own things as `app:<kind>:<id>` whatever host they were written
         * with, anything else as written.
         */
        fun target(link: String): String {
            if (link.startsWith("drive:")) return link
            // Mentions by name, whatever the case (they're resolved when asked about).
            if (link.startsWith("mention:")) return link.lowercase()
            AppLinks.parse(link)?.let { t -> return if (t.kind == com.syeyoung.assistant.refs.RefKind.FILE || t.kind == com.syeyoung.assistant.refs.RefKind.NOTE) "drive:${t.id}" else "app:${t.kind.wire}:${t.id}" }
            return link
        }
    }
}
