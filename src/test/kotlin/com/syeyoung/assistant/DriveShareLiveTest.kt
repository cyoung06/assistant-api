package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.drive.DriveService
import com.syeyoung.assistant.drive.DriveShare
import com.syeyoung.assistant.drive.ShareRequest
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.tasks.AttachmentRepository
import com.syeyoung.assistant.tasks.TaskRepository
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * [DriveShare] on a real drive (a scratch folder, deleted afterwards): shared files into a project's
 * folder, and a note with them beside it. Runs only with DRIVE_TEST_URL, DRIVE_TEST_USER and
 * DRIVE_TEST_PASSWORD_FILE set, like [WebDavLiveTest].
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveShareLiveTest {
    private val props = AppProperties(drive = AppProperties.Drive(
        url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
        password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
    ))
    private val dav = WebDav(props, WebClient.builder())
    // A schema of its own: uploads here aren't tasks' (the shared schema's are, with their tasks).
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres?schema=drive_share_test"))
    private val scratch = "_api-test-${UUID.randomUUID()}/"
    private val attachments = AttachmentRepository(db)
    private val share = DriveShare(dav, DriveService(dav, TaskRepository(db), db, props), attachments, props, Clock.systemUTC())

    init {
        runBlocking {
            db.sql("create schema if not exists drive_share_test").await()
            db.sql(File("src/main/resources/db/migration/V9__attachments.sql").readText().replace("create table", "create table if not exists").replace("create index", "create index if not exists").replace(" references tasks (id) on delete cascade", "")).await()
        }
    }

    @AfterAll
    fun cleanUp() = runBlocking { dav.delete(scratch) }

    private suspend fun read(path: String): String {
        val joined = DataBufferUtils.join(dav.get(path)!!.body!!).awaitSingle()
        return ByteArray(joined.readableByteCount()).also { joined.read(it); DataBufferUtils.release(joined) }.decodeToString()
    }

    @Test
    fun `files go into the folder, a note goes in Notes with them beside it`() = runBlocking {
        val pdf = attachments.insert("week 1.pdf", "application/pdf", "%PDF-1.4".toByteArray(), Instant.now())
        val png = attachments.insert("board.png", "image/png", byteArrayOf(1, 2, 3), Instant.now())
        val files = share.shareInto(scratch, ShareRequest("file", attachmentIds = listOf(pdf.id, png.id)))
        assertEquals(listOf("${scratch}week 1.pdf", "${scratch}board.png"), files.paths)
        // Again: numbered, not over what's there.
        assertEquals(listOf("${scratch}week 1 (2).pdf"), share.shareInto(scratch, ShareRequest("file", attachmentIds = listOf(pdf.id))).paths)

        val note = share.shareInto(scratch, ShareRequest("note", title = "Lecture board", text = "See https://x.org", attachmentIds = listOf(png.id)))
        assertTrue(note.paths[0].startsWith("${scratch}Notes/") && note.paths[0].endsWith(".md"), note.paths[0])
        assertEquals("${scratch}Notes/attachments/board.png", note.paths[1])
        assertEquals("# Lecture board\n\nSee https://x.org\n\n![board.png](attachments/board.png)\n\n", read(note.paths[0]))
        // Text alone, even asked as a file: a note.
        assertTrue(share.shareInto(scratch, ShareRequest("file", text = "just a link")).paths.single().endsWith(".md"))
    }
}
