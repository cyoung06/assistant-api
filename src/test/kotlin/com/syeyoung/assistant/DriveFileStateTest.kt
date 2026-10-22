package com.syeyoung.assistant

import com.syeyoung.assistant.drive.DriveFileState
import com.syeyoung.assistant.drive.FileStateChange
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** [DriveFileState] on the test database: what's kept about files, and that it follows them. */
class DriveFileStateTest {
    // A schema of its own: the shared test database's is Flyway's, made by the application tests.
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres?schema=drive_file_state_test"))
    private var now = Instant.parse("2026-10-22T09:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val state = DriveFileState(db, clock)

    @BeforeEach
    fun clean() = runBlocking {
        val sql = File("src/main/resources/db/migration/V19__drive_file_state.sql").readText()
            .replace("create table", "create table if not exists").replace("create index", "create index if not exists")
        db.sql("create schema if not exists drive_file_state_test").await()
        db.sql(sql).await()
        db.sql("delete from drive_file_state").await()
    }

    @Test
    fun `a change sets what's given and leaves the rest`() = runBlocking {
        state.change("Projects/A/book.pdf", FileStateChange(page = 12, zoom = 1.5f, tool = "pen"))
        val s = state.change("Projects/A/book.pdf", FileStateChange(starred = true))
        assertEquals(12, s.page)
        assertEquals(1.5f, s.zoom)
        assertEquals("pen", s.tool)
        assertTrue(s.starred)
        assertEquals(s, state.get("Projects/A/book.pdf"))
        assertFalse(state.get("Projects/A/other.pdf").starred)
        assertNull(state.get("Projects/A/other.pdf").page)
    }

    @Test
    fun `recent is latest first, starred by path`() = runBlocking {
        for ((i, p) in listOf("Projects/A/1.md", "Projects/A/2.md", "Projects/B/3.md").withIndex()) {
            now = Instant.parse("2026-10-22T09:00:00Z").plusSeconds(i * 60L)
            state.change(p, FileStateChange(opened = true))
        }
        state.change("Projects/B/", FileStateChange(starred = true))
        state.change("Projects/A/2.md", FileStateChange(starred = true))
        assertEquals(listOf("Projects/B/3.md", "Projects/A/2.md", "Projects/A/1.md"), state.recent(10).map { it.path })
        assertEquals(listOf("Projects/B/3.md"), state.recent(1).map { it.path })
        assertEquals(listOf("Projects/A/2.md", "Projects/B/"), state.starred().map { it.path })
    }

    @Test
    fun `it follows a file, and a folder with what's in it, when moved`() = runBlocking {
        state.change("Projects/A/notes.md", FileStateChange(starred = true))
        state.change("Projects/A/sub/", FileStateChange(starred = true))
        state.change("Projects/A/sub/book.pdf", FileStateChange(page = 3))
        state.change("Projects/A/subway.md", FileStateChange(starred = true))
        state.moved("Projects/A/notes.md", "Projects/B/notes.md")
        state.moved("Projects/A/sub", "Projects/B/renamed")
        assertTrue(state.get("Projects/B/notes.md").starred)
        assertTrue(state.get("Projects/B/renamed/").starred)
        assertEquals(3, state.get("Projects/B/renamed/book.pdf").page)
        // A sibling whose name only starts the same isn't taken along.
        assertTrue(state.get("Projects/A/subway.md").starred)
        assertFalse(state.get("Projects/A/notes.md").starred)
    }

    @Test
    fun `in the trash it's out of the lists, and back with it, and gone once purged`() = runBlocking {
        state.change("Projects/A/x.md", FileStateChange(starred = true, opened = true))
        state.moved("Projects/A/x.md", ".Trash/id/x.md")
        assertTrue(state.starred().isEmpty())
        assertTrue(state.recent(10).isEmpty())
        state.moved(".Trash/id/x.md", "Projects/A/x.md")
        assertEquals(listOf("Projects/A/x.md"), state.starred().map { it.path })
        state.moved("Projects/A/x.md", null)
        assertTrue(state.starred().isEmpty())
    }
}
