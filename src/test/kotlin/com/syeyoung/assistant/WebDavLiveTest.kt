package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.drive.WebDav
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.io.File
import java.util.UUID

/**
 * [WebDav] against a real drive (the httpd the drive runs), in a scratch folder of its own that's
 * deleted afterwards. Runs only with DRIVE_TEST_URL, DRIVE_TEST_USER and DRIVE_TEST_PASSWORD_FILE set.
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebDavLiveTest {
    private val dav = WebDav(
        AppProperties(drive = AppProperties.Drive(
            url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
            password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
        )),
        WebClient.builder(),
    )
    private val scratch = "_api-test-${UUID.randomUUID()}/"

    private fun bytes(b: ByteArray): Flux<DataBuffer> = Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(b))

    private suspend fun read(path: String, range: String? = null, ifNoneMatch: String? = null): Pair<Int, ByteArray>? {
        val r = dav.get(path, range, ifNoneMatch) ?: return null
        val body = DataBufferUtils.join(r.body ?: Flux.empty()).map { b -> ByteArray(b.readableByteCount()).also { b.read(it); DataBufferUtils.release(b) } }
            .defaultIfEmpty(ByteArray(0)).awaitSingle()
        return r.statusCode.value() to body
    }

    @AfterAll
    fun cleanUp() = runBlocking { dav.delete(scratch) }

    @Test
    fun `writes are conditional, reads stream with ranges and versions`() = runBlocking {
        dav.makeFolders(scratch + "a/b")
        val path = scratch + "a/b/note.txt"
        val first = dav.put(path, bytes("hello world".toByteArray()), 11, "text/plain", absent = true)
        assertNotNull(first)
        // There's one now: a create fails, as does a write based on another version.
        assertThrows<WebDav.Changed> { dav.put(path, bytes("x".toByteArray()), 1, "text/plain", absent = true) }
        assertThrows<WebDav.Changed> { dav.put(path, bytes("x".toByteArray()), 1, "text/plain", ifMatch = "\"not-it\"") }
        val second = dav.put(path, bytes("hello again".toByteArray()), 11, "text/plain", ifMatch = first)
        assertNotEquals(first, second)

        assertEquals(200 to "hello again", read(path)!!.let { it.first to String(it.second) })
        assertEquals(206 to "llo", read(path, range = "bytes=2-4")!!.let { it.first to String(it.second) })
        assertEquals(304, read(path, ifNoneMatch = second)!!.first)
        assertNull(read(scratch + "missing.txt"))
    }

    @Test
    fun `names survive the trip, whatever's in them`() = runBlocking {
        val folder = scratch + "names/"
        dav.makeFolders(folder)
        val names = listOf("한글 파일.md", "a+b c.txt", "100% done.txt", "hash#and?query.txt", "{braces}.txt", "semi;colon=eq.txt")
        for (n in names) dav.put(folder + n, bytes(n.toByteArray()), null, "text/plain", absent = true)
        assertEquals(names.sorted(), dav.list(folder)!!.map { it.name }.sorted())
        for (n in names) assertEquals(n, String(read(folder + n)!!.second))
    }

    @Test
    fun `folders copy, move and delete, with or without their slash`() = runBlocking {
        dav.makeFolders(scratch + "src")
        dav.put(scratch + "src/f.txt", bytes("f".toByteArray()), 1, "text/plain")
        dav.copy(scratch + "src", scratch + "copied")
        dav.move(scratch + "copied", scratch + "moved")
        assertTrue(dav.exists(scratch + "moved/f.txt"))
        assertFalse(dav.exists(scratch + "copied/"))
        // Onto something that's there: refused, not replaced.
        assertThrows<IllegalStateException> { dav.copy(scratch + "src", scratch + "moved") }
        dav.delete(scratch + "moved")
        assertFalse(dav.exists(scratch + "moved/"))
    }

    @Test
    fun `folders are told from files, with or without their slash`() = runBlocking {
        dav.makeFolders(scratch + "kind")
        dav.put(scratch + "kind.md", bytes("k".toByteArray()), 1, "text/markdown")
        assertTrue(dav.isFolder(scratch + "kind"))
        assertTrue(dav.isFolder(scratch + "kind/"))
        assertFalse(dav.isFolder(scratch + "kind.md"))
        assertFalse(dav.isFolder(scratch + "missing"))
    }

    @Test
    fun `a big upload streams through`() = runBlocking {
        val chunk = ByteArray(1 shl 20) { (it % 251).toByte() }
        val count = 24
        val body: Flux<DataBuffer> = Flux.range(0, count).map { DefaultDataBufferFactory.sharedInstance.wrap(chunk) }
        dav.makeFolders(scratch)
        dav.put(scratch + "big.bin", body, chunk.size.toLong() * count, "application/octet-stream", absent = true)
        assertEquals(chunk.size.toLong() * count, dav.list(scratch)!!.single { it.name == "big.bin" }.size)
        val (_, tail) = read(scratch + "big.bin", range = "bytes=${chunk.size * count - 10}-")!!
        assertArrayEquals(chunk.copyOfRange(chunk.size - 10, chunk.size), tail)
    }
}
