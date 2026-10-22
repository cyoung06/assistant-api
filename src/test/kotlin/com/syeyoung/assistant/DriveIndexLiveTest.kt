package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.drive.DriveIndex
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.refs.AppLinks
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
 * [DriveIndex] on a real drive (a scratch folder, deleted afterwards): files found by name, and the
 * notes linking to a task or a file. Runs only with DRIVE_TEST_URL, DRIVE_TEST_USER and
 * DRIVE_TEST_PASSWORD_FILE set, like [WebDavLiveTest].
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveIndexLiveTest {
    private val props = AppProperties(drive = AppProperties.Drive(
        url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
        password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
    ))
    private val dav = WebDav(props, WebClient.builder())
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres"))
    private val scratch = "_api-test-${UUID.randomUUID()}/"
    private val index = DriveIndex(dav, db, props, Clock.systemUTC())

    init {
        runBlocking {
            for (m in listOf("V16__sync.sql", "V22__drive_index.sql")) {
                db.sql(File("src/main/resources/db/migration/$m").readText().replace("create table", "create table if not exists").replace("create index", "create index if not exists")).await()
            }
            dav.makeFolders("${scratch}확률/Notes")
        }
    }

    @AfterAll
    fun cleanUp() = runBlocking { dav.delete(scratch) }

    private suspend fun write(path: String, text: String) {
        val bytes = text.toByteArray()
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), bytes.size.toLong(), "text/markdown")
    }

    @Test
    fun `notes link to tasks and files, found from either end`() = runBlocking {
        val task = "https://assistant.example/task/${UUID.randomUUID()}"
        write("${scratch}확률/week 1 slides.pdf", "%PDF")
        write("${scratch}확률/Notes/hw3.md", "# HW3\n\nFor [#12 HW3]($task), from [the slides](../week%201%20slides.pdf).\n")
        write("${scratch}확률/Notes/other.md", "Nothing here.")
        index.walk(scratch)

        // The same task written with another host still counts.
        assertEquals(listOf("${scratch}확률/Notes/hw3.md"), index.notesLinkingTo(task.replace("assistant.example", "localhost:5173")).map { it.path })
        assertEquals(listOf("${scratch}확률/Notes/hw3.md"), index.notesLinkingTo("drive:${scratch}확률/week 1 slides.pdf").map { it.path })
        assertTrue(index.search("slides", 5).any { it.path == "${scratch}확률/week 1 slides.pdf" })

        // Changed: walked again, what it links to now.
        write("${scratch}확률/Notes/hw3.md", "# HW3\n\nNo links now.\n")
        index.walk(scratch)
        assertEquals(emptyList<String>(), index.notesLinkingTo(task).map { it.path })
        // Gone: let go of.
        dav.delete("${scratch}확률/Notes/other.md")
        index.walk(scratch)
        assertEquals(null, index.get("${scratch}확률/Notes/other.md"))
    }

    @Test
    fun `a note made and saved live since the walk counts at once`() = runBlocking {
        index.walk(scratch)
        val path = "${scratch}확률/Notes/new.md"
        write(path, "See @HW3")
        // As live editing saves it.
        db.sql("insert into sync_docs (path, state, base_state, updated_at) values (:p, ''::bytea, ''::bytea, now()) on conflict (path) do update set updated_at = now()").bind("p", path).await()
        index.notesSaved()
        assertEquals(listOf(path), index.notesLinkingTo("mention:hw3").map { it.path })
    }

    @Test
    fun `a file's page in the app is the file`() {
        val url = AppLinks.file("https://a.example", "Projects/확률및통계/Course files/week 1.pdf")
        assertEquals("https://a.example/files/%ED%99%95%EB%A5%A0%EB%B0%8F%ED%86%B5%EA%B3%84/Course%20files/week%201.pdf", url)
        assertEquals("drive:Projects/확률및통계/Course files/week 1.pdf", DriveIndex.target(url))
    }
}
