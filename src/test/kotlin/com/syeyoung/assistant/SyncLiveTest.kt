package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.core.SyncDoc
import com.syeyoung.assistant.core.TextChange
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.sync.SyncDocs
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * [SyncDocs] with two devices on a note in a real drive (the httpd the drive runs, in a scratch
 * folder deleted afterwards) and the test database. Each device is the core's [SyncDoc], as the
 * apps use it, plus the text its editor would show (kept from the changes it's told about). Runs
 * only with DRIVE_TEST_URL, DRIVE_TEST_USER and DRIVE_TEST_PASSWORD_FILE set, like [WebDavLiveTest].
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncLiveTest {
    private val props = AppProperties(drive = AppProperties.Drive(
        url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
        password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
    ))
    private val dav = WebDav(props, WebClient.builder())
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres"))
    private val scratch = "_api-test-${UUID.randomUUID()}/"

    init {
        runBlocking {
            db.sql(File("src/main/resources/db/migration/V16__sync.sql").readText().replace("create table", "create table if not exists")).await()
        }
    }

    /** A device: its document, the text its editor shows, and its line to the server. */
    private inner class Device(val docs: SyncDocs) : SyncDocs.Peer {
        val doc = SyncDoc()
        val editor = mutableListOf<Char>()
        lateinit var room: SyncDocs.Room
        private val inbox = ArrayDeque<ByteArray>()

        override fun send(message: ByteArray) = synchronized(inbox) { inbox.addLast(message) }

        suspend fun join(path: String) {
            room = docs.join(path, this)!!
            send(room.doc.start())
            docs.receive(room, this, doc.start())
            drain()
        }

        /** Handles what the server sent, answering as y-websocket does. */
        suspend fun drain() {
            while (true) {
                val m = synchronized(inbox) { inbox.removeFirstOrNull() } ?: return
                val got = doc.receive(m)
                show(got.changes)
                got.reply?.let { docs.receive(room, this, it) }
            }
        }

        suspend fun type(from: Int, to: Int, insert: String) {
            show(listOf(TextChange(from.toUInt(), to.toUInt(), insert)))
            docs.receive(room, this, doc.edit(from.toUInt(), to.toUInt(), insert))
        }

        private fun show(changes: List<TextChange>) = changes.forEach { c ->
            repeat((c.to - c.from).toInt()) { editor.removeAt(c.from.toInt()) }
            editor.addAll(c.from.toInt(), c.insert.toList())
        }

        val shown get() = editor.joinToString("")
    }

    private suspend fun write(path: String, text: String, ifMatch: String? = null) =
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(text.toByteArray())), null, "text/markdown", ifMatch = ifMatch)

    private suspend fun read(path: String): String {
        val r = dav.get(path)!!
        val b = DataBufferUtils.join(r.body!!).awaitSingle()
        return ByteArray(b.readableByteCount()).also { b.read(it); DataBufferUtils.release(b) }.decodeToString()
    }

    @AfterAll
    fun cleanUp() = runBlocking {
        dav.delete(scratch)
        db.sql("delete from sync_docs where path like :p").bind("p", "$scratch%").await()
    }

    @Test
    fun `two devices edit a note together, and the file follows`() = runBlocking {
        val docs = SyncDocs(dav, props, db, Clock.systemUTC())
        dav.makeFolders(scratch)
        val note = scratch + "note.md"
        write(note, "하나 둘 셋\n")

        val a = Device(docs)
        val b = Device(docs)
        a.join(note)
        b.join(note)
        assertEquals("하나 둘 셋\n", a.shown)
        assertEquals("하나 둘 셋\n", b.shown)

        // At the same time on both: A after "하나", B replaces "셋" with an emoji.
        a.type(2, 2, "!")
        b.type(5, 6, "😀")
        a.drain(); b.drain()
        assertEquals("하나! 둘 😀\n", a.shown)
        assertEquals(a.shown, b.shown)

        // A second after the last edit, the file has it.
        delay(2500)
        assertEquals("하나! 둘 😀\n", read(note))

        // Someone edits the file elsewhere (adds a line) while A types at the start.
        write(note, "하나! 둘 😀\n밖에서 쓴 줄\n", ifMatch = dav.etag(note))
        a.type(0, 0, "# ")
        delay(2500)
        a.drain(); b.drain()
        // Both kept: in the file, and on both devices.
        assertEquals("# 하나! 둘 😀\n밖에서 쓴 줄\n", read(note))
        assertEquals(read(note), a.shown)
        assertEquals(read(note), b.shown)

        // Everyone leaves; opened again, it's as it was.
        docs.leave(a.room, a)
        docs.leave(b.room, b)
        val c = Device(docs)
        c.join(note)
        assertEquals("# 하나! 둘 😀\n밖에서 쓴 줄\n", c.shown)
        docs.leave(c.room, c)
    }

    @Test
    fun `a note changed elsewhere while closed opens as the file is now`() = runBlocking {
        val docs = SyncDocs(dav, props, db, Clock.systemUTC())
        dav.makeFolders(scratch)
        val note = scratch + "closed.md"
        write(note, "first\n")
        val a = Device(docs)
        a.join(note)
        a.type(6, 6, "second\n")
        docs.leave(a.room, a)
        assertEquals("first\nsecond\n", read(note))

        write(note, "zero\nfirst\nsecond\n", ifMatch = dav.etag(note))
        val b = Device(docs)
        b.join(note)
        assertEquals("zero\nfirst\nsecond\n", b.shown)
        docs.leave(b.room, b)
    }

    @Test
    fun `writes through the drive reach the devices, and don't undo what they type meanwhile`() = runBlocking {
        // What the device types stays unwritten while the file is written elsewhere.
        val docs = SyncDocs(dav, props, db, Clock.systemUTC()).apply { settle = 15_000 }
        dav.makeFolders(scratch)
        val note = scratch + "written.md"
        write(note, "a\n")
        val dev = Device(docs)
        dev.join(note)

        // The device types; before that's written, the file is written twice elsewhere (an app without live editing).
        dev.type(2, 2, "typed\n")
        write(note, "a\nb\n", ifMatch = dav.etag(note))
        docs.written(note)
        write(note, "a\nb\nc\n", ifMatch = dav.etag(note))
        docs.written(note)
        dev.drain()
        // Nothing lost (the two inserted at the same place at once go in an order of Yjs' choosing, the same everywhere).
        assertEquals(listOf("a", "b", "c", "typed"), dev.shown.lines().filter { it.isNotEmpty() }.sorted())
        assertEquals(true, dev.shown.startsWith("a\n") && dev.shown.indexOf("b\n") < dev.shown.indexOf("c\n"), dev.shown)
        // Leaving writes it, as shown.
        val shown = dev.shown
        docs.leave(dev.room, dev)
        assertEquals(shown, read(note))
    }

    @Test
    fun `a note moved while open is written first and follows`() = runBlocking {
        val docs = SyncDocs(dav, props, db, Clock.systemUTC())
        dav.makeFolders(scratch)
        val note = scratch + "before.md"
        val after = scratch + "after.md"
        write(note, "x\n")
        val dev = Device(docs)
        dev.join(note)
        dev.type(2, 2, "y\n")
        // As the drive's move does: let go, move, then follow.
        docs.release(note)
        dav.move(note, after)
        docs.moved(note, after)
        assertEquals("x\ny\n", read(after))
        // Opened at its new place, it's the same document (nothing typed twice).
        val again = Device(docs)
        again.join(after)
        assertEquals("x\ny\n", again.shown)
        docs.leave(again.room, again)
    }

    @Test
    fun `a note that isn't there can't be opened`() = runBlocking {
        val docs = SyncDocs(dav, props, db, Clock.systemUTC())
        assertNull(docs.join(scratch + "missing.md") { })
    }
}
