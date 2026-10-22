package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.drive.DriveFileState
import com.syeyoung.assistant.drive.DriveTrash
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.sync.Blobs
import com.syeyoung.assistant.sync.SyncDocs
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * [DriveTrash] on a real drive (a scratch folder, deleted afterwards; what it trashes is purged) and
 * the test database. Runs only with DRIVE_TEST_URL, DRIVE_TEST_USER and DRIVE_TEST_PASSWORD_FILE set.
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveTrashLiveTest {
    private val props = AppProperties(drive = AppProperties.Drive(
        url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
        password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
    ))
    private val dav = WebDav(props, WebClient.builder())
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres"))
    private val scratch = "_api-test-${UUID.randomUUID()}"
    private val docs = SyncDocs(dav, props, db, Clock.systemUTC(), Blobs(db, Clock.systemUTC()))
    private val trash = DriveTrash(dav, db, docs, Clock.systemUTC(), DriveFileState(db, Clock.systemUTC()))

    init {
        runBlocking {
            for (m in listOf("V16__sync.sql", "V17__sync_blobs.sql", "V18__drive_trash.sql", "V19__drive_file_state.sql")) {
                db.sql(File("src/main/resources/db/migration/$m").readText().replace("create table", "create table if not exists").replace("create index", "create index if not exists")).await()
            }
            dav.makeFolders("$scratch/inner")
        }
    }

    @AfterAll
    fun cleanUp() = runBlocking {
        for (t in trash.list().filter { it.path.startsWith(scratch) }) trash.purge(t.id)
        dav.delete("$scratch/")
    }

    private suspend fun write(path: String, text: String) {
        val bytes = text.toByteArray()
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), bytes.size.toLong(), "text/markdown")
    }

    @Test
    fun `a file goes to the trash and comes back where it was`() = runBlocking {
        write("$scratch/note.md", "hello")
        val t = trash.trash("$scratch/note.md")
        assertFalse(dav.exists("$scratch/note.md"))
        assertTrue(trash.list().any { it.id == t.id && it.path == "$scratch/note.md" && !it.folder && it.size == 5L })
        assertEquals("$scratch/note.md", trash.restore(t.id))
        assertTrue(dav.exists("$scratch/note.md"))
        assertFalse(trash.list().any { it.id == t.id })
    }

    @Test
    fun `restoring over something new numbers it`() = runBlocking {
        write("$scratch/clash.md", "old")
        val t = trash.trash("$scratch/clash.md")
        write("$scratch/clash.md", "new")
        assertEquals("$scratch/clash (2).md", trash.restore(t.id))
    }

    @Test
    fun `a folder goes with what's in it, and its parents come back if gone`() = runBlocking {
        dav.makeFolders("$scratch/gone/deep")
        write("$scratch/gone/deep/a.md", "a")
        val t = trash.trash("$scratch/gone/deep")
        assertTrue(t.folder)
        dav.delete("$scratch/gone/")
        assertEquals("$scratch/gone/deep/", trash.restore(t.id))
        assertTrue(dav.exists("$scratch/gone/deep/a.md"))
    }

    @Test
    fun `purging deletes for good`() = runBlocking {
        write("$scratch/bye.md", "bye")
        val t = trash.trash("$scratch/bye.md")
        assertTrue(trash.purge(t.id))
        assertFalse(trash.list().any { it.id == t.id })
        assertFalse(dav.exists("${DriveTrash.TRASH}/${t.id}/bye.md"))
    }
}
