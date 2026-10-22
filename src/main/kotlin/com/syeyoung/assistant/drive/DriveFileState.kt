package com.syeyoung.assistant.drive

import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** What's kept about a drive file: starred, when last opened, and where a PDF was left. */
data class FileState(
    val path: String,
    val starred: Boolean = false,
    val openedAt: Instant? = null,
    /** A PDF's page (from 0) when last left, its zoom, and the tool in hand. */
    val page: Int? = null,
    val zoom: Float? = null,
    val tool: String? = null,
)

/** A change to a file's state: what's given is set, what's null is left as it is. */
data class FileStateChange(
    val starred: Boolean? = null,
    /** Opened now. */
    val opened: Boolean? = null,
    val page: Int? = null,
    val zoom: Float? = null,
    val tool: String? = null,
)

/**
 * The person's state of each drive file (one person: the app is theirs), kept in the database by
 * path: starred files, recently opened ones, and where each PDF was left. It follows a file when it's
 * moved or renamed ([moved]); one deleted takes it to the trash with it, and back ([moved] both ways).
 */
@Component
class DriveFileState(private val db: DatabaseClient, private val clock: Clock) {

    suspend fun get(path: String): FileState =
        db.sql("select * from drive_file_state where path = :path").bind("path", path).map { r -> row(r) }.awaitOneOrNull() ?: FileState(path)

    suspend fun change(path: String, c: FileStateChange): FileState {
        val now = Instant.now(clock)
        val had = get(path)
        val next = had.copy(
            starred = c.starred ?: had.starred,
            openedAt = if (c.opened == true) now else had.openedAt,
            page = c.page ?: had.page,
            zoom = c.zoom ?: had.zoom,
            tool = c.tool ?: had.tool,
        )
        var q = db.sql(
            """
            insert into drive_file_state (path, starred, opened_at, page, zoom, tool, updated_at) values (:path, :starred, :opened, :page, :zoom, :tool, :now)
            on conflict (path) do update set starred = excluded.starred, opened_at = excluded.opened_at, page = excluded.page, zoom = excluded.zoom, tool = excluded.tool, updated_at = excluded.updated_at
            """.trimIndent(),
        ).bind("path", path).bind("starred", next.starred).bind("now", now)
        q = next.openedAt?.let { q.bind("opened", it) } ?: q.bindNull("opened", Instant::class.java)
        q = next.page?.let { q.bind("page", it) } ?: q.bindNull("page", Integer::class.java)
        q = next.zoom?.let { q.bind("zoom", it) } ?: q.bindNull("zoom", java.lang.Float::class.java)
        q = next.tool?.let { q.bind("tool", it) } ?: q.bindNull("tool", String::class.java)
        q.fetch().awaitRowsUpdated()
        return next
    }

    /** The files opened most recently, latest first. */
    suspend fun recent(limit: Int): List<FileState> =
        db.sql("select * from drive_file_state where opened_at is not null and path not like '.Trash/%' order by opened_at desc limit :limit").bind("limit", limit).map { r -> row(r) }.flow().toList()

    /** The starred files and folders, by path. */
    suspend fun starred(): List<FileState> =
        db.sql("select * from drive_file_state where starred and path not like '.Trash/%' order by path").map { r -> row(r) }.flow().toList()

    /** [from] (a file, or a folder with what's in it) is now at [to]; null: gone for good. */
    suspend fun moved(from: String, to: String?) {
        val folder = from.trimEnd('/') + "/"
        if (to == null) {
            db.sql("delete from drive_file_state where path = :p or path = :folder or path like :under")
                .bind("p", from.trimEnd('/')).bind("folder", folder).bind("under", like(folder)).fetch().awaitRowsUpdated()
            return
        }
        val target = to.trimEnd('/')
        db.sql("update drive_file_state set path = :to || substr(path, length(:from) + 1) where path = :from or path = :folder or path like :under")
            .bind("to", target).bind("from", from.trimEnd('/')).bind("folder", folder).bind("under", like(folder)).fetch().awaitRowsUpdated()
    }

    private fun like(folder: String) = folder.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun row(r: io.r2dbc.spi.Readable) = FileState(
        r.get("path", String::class.java)!!,
        r.get("starred", java.lang.Boolean::class.java)?.booleanValue() ?: false,
        r.get("opened_at", Instant::class.java),
        r.get("page", Integer::class.java)?.toInt(),
        r.get("zoom", java.lang.Float::class.java)?.toFloat(),
        r.get("tool", String::class.java),
    )
}
