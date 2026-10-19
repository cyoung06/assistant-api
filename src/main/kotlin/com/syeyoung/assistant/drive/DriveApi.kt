package com.syeyoung.assistant.drive

import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.util.UUID

data class DriveInfo(
    val enabled: Boolean,
    /** Where devices connect (e.g. Finder's Connect to Server), with [username]. */
    val url: String,
    val username: String,
)

data class DriveFolder(val path: String, val entries: List<DriveEntry>)

/** The drive: what's in a project's folder, and reading and writing its files. */
@RestController
@RequestMapping("/api/v1/drive")
class DriveController(private val drive: DriveService, private val dav: WebDav, private val props: AppProperties) {

    @GetMapping
    fun driveInfo() = DriveInfo(drive.enabled, props.drive.publicUrl, props.drive.username)

    /** A project's folder (or a folder inside it, [path] relative to the project's). */
    @GetMapping("/projects/{id}")
    suspend fun projectFolder(@PathVariable id: UUID, @RequestParam(defaultValue = "") path: String): DriveFolder {
        requireEnabled()
        val folder = drive.folderOf(id) + safe(path).let { if (it.isEmpty()) "" else "$it/" }
        return DriveFolder(folder, dav.list(folder) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND))
    }

    /** Any folder of the drive ([path] from its root). */
    @GetMapping("/folder")
    suspend fun folder(@RequestParam(defaultValue = "") path: String): DriveFolder {
        requireEnabled()
        val folder = safe(path).let { if (it.isEmpty()) "" else "$it/" }
        return DriveFolder(folder, dav.list(folder) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND))
    }

    /** A file's contents. */
    @GetMapping("/file")
    suspend fun readFile(@RequestParam path: String): ResponseEntity<Flux<DataBuffer>> {
        requireEnabled()
        val r = dav.get(safe(path)) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val type = r.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)
        val body = DataBufferUtils.readInputStream({ r.body() }, DefaultDataBufferFactory.sharedInstance, 64 * 1024)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
            .apply { r.headers().firstValueAsLong("Content-Length").ifPresent { contentLength(it) } }
            .body(body)
    }

    /** Writes a file (making its folder); the request body is the contents. */
    @PutMapping("/file")
    suspend fun writeFile(@RequestParam path: String, request: ServerHttpRequest): ResponseEntity<Unit> {
        requireEnabled()
        val p = safe(path)
        if (p.isEmpty() || p.endsWith("/")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a file path is needed")
        dav.makeFolders(p.substringBeforeLast('/', ""))
        val bytes = DataBufferUtils.join(request.body).awaitSingle().let { buf -> ByteArray(buf.readableByteCount()).also { buf.read(it); DataBufferUtils.release(buf) } }
        dav.put(p, bytes.inputStream(), bytes.size.toLong(), request.headers.contentType?.toString())
        return ResponseEntity.noContent().build()
    }

    private fun requireEnabled() {
        if (!drive.enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The drive isn't set up")
    }

    /** A drive path without "..", leading slashes or empty parts. */
    private fun safe(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad path")
        return parts.joinToString("/")
    }
}
