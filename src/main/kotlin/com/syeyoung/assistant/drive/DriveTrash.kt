package com.syeyoung.assistant.drive

import com.syeyoung.assistant.sync.SyncDocs
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Something deleted, as the trash keeps it: where it was ([path]; a folder's ends with "/"), and when it went. */
data class Trashed(val id: UUID, val path: String, val name: String, val folder: Boolean, val size: Long, val deletedAt: Instant)

/**
 * The drive's trash: what's deleted (from the apps, the web, or Finder) is moved to `.Trash/<id>/`
 * on the drive, not deleted, and kept [KEEP] days, restorable to where it was. A note or notebook
 * there keeps its live document (it follows the file, see [SyncDocs.moved]), so restoring brings it
 * back as it was. Deleting straight over WebDAV (outside the API) still deletes.
 */
@Component
class DriveTrash(private val dav: WebDav, private val db: DatabaseClient, private val live: SyncDocs, private val clock: Clock, private val state: DriveFileState) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Where [id]'s item is kept (without a folder's "/", as paths are moved: see [SyncDocs.moved]). */
    private fun kept(id: UUID, name: String) = "$TRASH/$id/$name"

    /** Moves [path] (a file, or a folder with what's in it) to the trash; what's kept of it. */
    suspend fun trash(path: String): Trashed {
        val folder = dav.isFolder(path)
        val name = path.trimEnd('/').substringAfterLast('/')
        val size = if (folder) 0 else dav.list(path.substringBeforeLast('/', ""))?.find { it.path == path }?.size ?: 0
        val item = Trashed(UUID.randomUUID(), path.trimEnd('/') + if (folder) "/" else "", name, folder, size, Instant.now(clock))
        val from = path.trimEnd('/')
        val to = kept(item.id, name)
        dav.makeFolders("$TRASH/${item.id}")
        live.release(from)
        dav.move(from, to)
        live.moved(from, to)
        state.moved(from, to)
        db.sql("insert into drive_trash (id, path, name, folder, size, deleted_at) values (:id, :path, :name, :folder, :size, :at)")
            .bind("id", item.id).bind("path", item.path).bind("name", name).bind("folder", folder).bind("size", size).bind("at", item.deletedAt)
            .fetch().awaitRowsUpdated()
        return item
    }

    /** What's in the trash, latest first. */
    suspend fun list(): List<Trashed> =
        db.sql("select id, path, name, folder, size, deleted_at from drive_trash order by deleted_at desc")
            .map { r -> row(r) }.flow().toList()

    private fun row(r: io.r2dbc.spi.Readable) = Trashed(
        r.get("id", UUID::class.java)!!, r.get("path", String::class.java)!!, r.get("name", String::class.java)!!,
        r.get("folder", java.lang.Boolean::class.java)!!.booleanValue(), r.get("size", java.lang.Long::class.java)!!.toLong(), r.get("deleted_at", Instant::class.java)!!,
    )

    private suspend fun find(id: UUID): Trashed? =
        db.sql("select id, path, name, folder, size, deleted_at from drive_trash where id = :id").bind("id", id).map { r -> row(r) }.awaitOneOrNull()

    /**
     * Puts [id] back where it was (its folders made again if they're gone; numbered, "Notes (2).md",
     * if something has taken its place). Where it went; null if it isn't in the trash.
     */
    suspend fun restore(id: UUID): String? {
        val item = find(id) ?: return null
        val from = kept(id, item.name)
        val bare = item.path.trimEnd('/')
        val dot = item.name.lastIndexOf('.').takeIf { it > 0 && !item.folder }
        var target = bare
        var n = 2
        while (dav.exists(target)) {
            target = if (dot == null) "$bare ($n)" else bare.dropLast(item.name.length - dot) + " ($n)" + item.name.substring(dot)
            n++
        }
        dav.makeFolders(target.substringBeforeLast('/', ""))
        live.release(from)
        dav.move(from, target)
        live.moved(from, target)
        state.moved(from, target)
        dav.delete("$TRASH/$id/")
        db.sql("delete from drive_trash where id = :id").bind("id", id).fetch().awaitRowsUpdated()
        return target + if (item.folder) "/" else ""
    }

    /** Deletes [id] for good (asked for: emptying the trash, or one item from it). */
    suspend fun purge(id: UUID): Boolean {
        val item = find(id) ?: return false
        val at = kept(id, item.name)
        live.release(at)
        dav.delete("$TRASH/$id/")
        live.moved(at, null)
        state.moved(at, null)
        db.sql("delete from drive_trash where id = :id").bind("id", id).fetch().awaitRowsUpdated()
        return true
    }

    /** Lets go of what's been in the trash longer than [KEEP], every few hours. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scope.launch {
            while (isActive) {
                runCatching {
                    val old = db.sql("select id from drive_trash where deleted_at < :before")
                        .bind("before", Instant.now(clock).minus(KEEP))
                        .map { r -> r.get("id", UUID::class.java)!! }.flow().toList()
                    for (id in old) purge(id)
                    if (old.isNotEmpty()) log.info("Trash: {} item(s) older than {} days let go", old.size, KEEP.toDays())
                }.onFailure { log.warn("Trash: expiring failed: {}", it.toString()) }
                delay(Duration.ofHours(6).toMillis())
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        /** The trash's folder on the drive (kept out of listings). */
        const val TRASH = ".Trash"
        val KEEP: Duration = Duration.ofDays(30)
    }
}
