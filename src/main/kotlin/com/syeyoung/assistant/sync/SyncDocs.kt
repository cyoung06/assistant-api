package com.syeyoung.assistant.sync

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.core.SyncDoc
import com.syeyoung.assistant.drive.DriveService
import com.syeyoung.assistant.drive.WebDav
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Notes edited live (y-websocket, see [SyncSocket]): each open note is one Yjs document here
 * ([SyncDoc], from the shared core), kept in the database, with the devices on it. This is the only
 * writer of an open note's file: a second after edits stop, the text goes to the drive. An edit made
 * to the file elsewhere (another app, through WebDAV) is merged in as an edit, so nothing is lost
 * either way.
 */
@Component
class SyncDocs(private val dav: WebDav, private val props: AppProperties, private val db: DatabaseClient, private val clock: Clock) {
    /** How long after the last edit a note is written. */
    internal var settle = 1000L

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rooms = ConcurrentHashMap<String, Room>()
    private val opening = ConcurrentHashMap<String, Mutex>()

    /** A device on a note: how to send it a message, and to let it go (the note moved or was deleted). */
    fun interface Peer {
        fun send(message: ByteArray)
        fun close() {}
    }

    /** An open note: its document and the devices on it. */
    inner class Room(val path: String, val doc: SyncDoc, var etag: String?, var base: ByteArray) {
        val peers = CopyOnWriteArraySet<Peer>()
        /** One change to the document (and its saving) at a time. */
        val lock = Mutex()
        var save: Job? = null
        var dirty = false
    }

    /** Notes that can be opened this way: Markdown in the drive, not a course file (those only read). */
    fun syncable(path: String) = props.drive.enabled && path.endsWith(".md", ignoreCase = true) && !DriveService.isCourseFile(path)

    /** [peer] joins the note at [path] (opened, from the database or the file, if no one has it open); null if there's no such note. */
    suspend fun join(path: String, peer: Peer): Room? {
        val room = rooms[path] ?: opening.computeIfAbsent(path) { Mutex() }.withLock { rooms[path] ?: open(path)?.also { rooms[path] = it } } ?: return null
        room.peers += peer
        return room
    }

    /** [peer] left: with no one left on the note, it's saved and let go. */
    suspend fun leave(room: Room, peer: Peer) {
        room.peers -= peer
        if (room.peers.isNotEmpty()) return
        room.save?.cancel()
        save(room)
        room.lock.withLock { if (room.peers.isEmpty()) rooms.remove(room.path, room) }
    }

    /** A message from [peer]: answered, passed on to the others, and the note saved soon after if it changed. */
    suspend fun receive(room: Room, peer: Peer, message: ByteArray) {
        val got = room.lock.withLock { withContext(Dispatchers.Default) { room.doc.receive(message) } }
        got.reply?.let(peer::send)
        got.broadcast?.let { out ->
            // Awareness (who's there, their cursors) goes to everyone, the sender too (y-websocket's
            // own server does the same, and its clients take that as the connection being alive).
            val awareness = message.firstOrNull() == MESSAGE_AWARENESS
            room.peers.forEach { if (awareness || it !== peer) it.send(out) }
        }
        if (got.changes.isNotEmpty()) later(room)
    }

    /**
     * Before what's at [path] (a note, or a folder with notes in it) is moved or deleted: its open
     * notes are written now, and their devices let go (writing on would make the old file again).
     */
    suspend fun release(path: String) {
        for (room in rooms.values.filter { under(it.path, path) }) {
            room.save?.cancel()
            save(room)
            rooms.remove(room.path, room)
            room.peers.forEach { it.close() }
        }
    }

    /** After [from] was moved to [to] (null: deleted): what's kept of its notes follows. */
    suspend fun moved(from: String, to: String?) {
        if (to == null) {
            db.sql("delete from sync_docs where path = :p or path like :under").bind("p", from).bind("under", like(from)).fetch().awaitRowsUpdated()
        } else {
            db.sql("update sync_docs set path = :to || substr(path, length(:from) + 1) where path = :from or path like :under")
                .bind("to", to).bind("from", from).bind("under", like(from)).fetch().awaitRowsUpdated()
        }
    }

    /** Before what's at [path] is copied: its open notes written, so the copy has what's typed. */
    suspend fun flush(path: String) {
        for (room in rooms.values.filter { under(it.path, path) }) {
            room.save?.cancel()
            save(room)
        }
    }

    /** The note at [path] was written through the drive (not live): if it's open, that's merged in now. */
    suspend fun written(path: String) {
        val room = rooms[path] ?: return
        room.lock.withLock {
            val file = read(path) ?: return
            if (file.second == room.etag) return
            room.base = merge(room, file.first)
            room.etag = file.second
            store(path, room.doc.state(), room.base, room.etag)
            if (room.doc.text() != file.first) later(room)
        }
    }

    private fun under(note: String, path: String) = note == path || note.startsWith(path.trimEnd('/') + "/")
    private fun like(folder: String) = folder.trimEnd('/').replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"

    private suspend fun open(path: String): Room? {
        val file = read(path)
        val row = db.sql("select state, base_state, file_etag from sync_docs where path = :path").bind("path", path)
            .map { r -> Triple(bytes(r.get("state", ByteBuffer::class.java)!!), bytes(r.get("base_state", ByteBuffer::class.java)!!), r.get("file_etag", String::class.java)) }
            .awaitOneOrNull()
        if (file == null) {
            // Deleted (or moved) since: what was kept of it goes too.
            if (row != null) forget(path)
            return null
        }
        if (row == null) {
            val doc = withContext(Dispatchers.Default) { SyncDoc.fromText(file.first) }
            val state = doc.state()
            store(path, state, state, file.second)
            return Room(path, doc, file.second, state)
        }
        val (state, base, etag) = row
        val room = Room(path, withContext(Dispatchers.Default) { SyncDoc.fromState(state) }, etag, base)
        // Changed elsewhere while no one had it open.
        if (file.second != etag) {
            room.base = merge(room, file.first)
            room.etag = file.second
            store(path, room.doc.state(), room.base, room.etag)
            // Opened with the file's change in it: written as merged (it may hold edits the file hasn't).
            if (room.doc.text() != file.first) later(room)
        }
        return room
    }

    /**
     * The file's text changed elsewhere to [text]: that change, made to the document as it was when
     * the file last matched, merged in. Returns that document with the change (its text is the
     * file's), the new base for the next change made elsewhere.
     */
    private suspend fun merge(room: Room, text: String): ByteArray = withContext(Dispatchers.Default) {
        val fork = SyncDoc.fromState(room.base)
        val before = fork.text()
        if (before != text) {
            val (from, to, insert) = difference(before, text)
            val got = room.doc.receive(fork.edit(from.toUInt(), to.toUInt(), insert))
            got.broadcast?.let { out -> room.peers.forEach { it.send(out) } }
            log.info("{}: changed elsewhere, merged in", room.path)
        }
        fork.state()
    }

    private fun later(room: Room) {
        room.dirty = true
        room.save?.cancel()
        room.save = scope.launch {
            delay(settle)
            save(room)
        }
    }

    /** Writes the note's text to its file and its document to the database (if anything changed). */
    private suspend fun save(room: Room) {
        room.lock.withLock {
            if (!room.dirty) return
            room.dirty = false
            try {
                // A few tries: each refusal means the file changed elsewhere, which is merged in first.
                repeat(3) {
                    val text = room.doc.text()
                    val state = room.doc.state()
                    try {
                        val etag = dav.put(room.path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(text.toByteArray())), null, "text/markdown; charset=utf-8", ifMatch = room.etag)
                        room.etag = etag
                        room.base = state
                        store(room.path, state, state, etag)
                        return
                    } catch (e: WebDav.Changed) {
                        val file = read(room.path) ?: run { log.info("{}: deleted elsewhere; not written", room.path); forget(room.path); return }
                        room.base = merge(room, file.first)
                        room.etag = file.second
                    }
                }
                error("it kept changing elsewhere")
            } catch (e: Exception) {
                room.dirty = true
                log.warn("{}: not saved: {}", room.path, e.toString())
            }
        }
    }

    /** The file's text and version; null if it isn't there. */
    private suspend fun read(path: String): Pair<String, String?>? {
        val r = dav.get(path) ?: return null
        val joined = DataBufferUtils.join(r.body ?: Flux.empty()).awaitSingleOrNull()
        val text = joined?.let { b -> ByteArray(b.readableByteCount()).also { b.read(it); DataBufferUtils.release(b) }.decodeToString() } ?: ""
        return text to dav.etag(path)
    }

    private suspend fun store(path: String, state: ByteArray, base: ByteArray, etag: String?) {
        db.sql(
            """
            insert into sync_docs (path, state, base_state, file_etag, updated_at) values (:path, :state, :base, :etag, :now)
            on conflict (path) do update set state = excluded.state, base_state = excluded.base_state, file_etag = excluded.file_etag, updated_at = excluded.updated_at
            """.trimIndent(),
        ).bind("path", path).bind("state", ByteBuffer.wrap(state)).bind("base", ByteBuffer.wrap(base))
            .let { if (etag != null) it.bind("etag", etag) else it.bindNull("etag", String::class.java) }
            .bind("now", Instant.now(clock))
            .fetch().awaitRowsUpdated()
    }

    private suspend fun forget(path: String) {
        db.sql("delete from sync_docs where path = :path").bind("path", path).fetch().awaitRowsUpdated()
    }

    /** On shutdown: what's unsaved is written. */
    @PreDestroy
    fun stop() {
        runBlocking { rooms.values.forEach { it.save?.cancel(); save(it) } }
        scope.cancel()
    }

    companion object {
        /** y-protocols' awareness message type. */
        private const val MESSAGE_AWARENESS: Byte = 1

        private fun bytes(b: ByteBuffer) = ByteArray(b.remaining()).also { b.get(it) }

        /**
         * The one change that turns [before] into [after]: what's between their common start and end,
         * in UTF-16 units (as the document counts).
         */
        fun difference(before: String, after: String): Triple<Int, Int, String> {
            var start = 0
            while (start < before.length && start < after.length && before[start] == after[start]) start++
            // Not inside a surrogate pair.
            if (start > 0 && Character.isHighSurrogate(before[start - 1])) start--
            var end = 0
            while (end < before.length - start && end < after.length - start && before[before.length - 1 - end] == after[after.length - 1 - end]) end++
            if (end > 0 && Character.isLowSurrogate(before[before.length - end])) end--
            return Triple(start, before.length - end, after.substring(start, after.length - end))
        }
    }
}
