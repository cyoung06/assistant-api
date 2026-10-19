package com.syeyoung.assistant.drive

import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.RequestHeader
import java.nio.file.Files
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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

data class DriveCopied(val path: String)

private const val READ_ONLY = "Course files are kept as PLMS has them; copy one out to change it"

/** The drive: what's in a project's folder, and reading and writing its files. */
@RestController
@RequestMapping("/api/v1/drive")
class DriveController(private val drive: DriveService, private val dav: WebDav, private val thumbs: DriveThumbs, private val props: AppProperties) {

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

    /**
     * A file's contents. Ranges (for media seeking) and ETags (If-None-Match; the ETag also goes
     * back as If-Match when writing, to not overwrite someone else's change) are passed through.
     */
    @GetMapping("/file")
    suspend fun readFile(
        @RequestParam path: String,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<Flux<DataBuffer>> {
        requireEnabled()
        val r = dav.get(safe(path), range, ifNoneMatch?.let(::strong)) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val type = r.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)
        val body = DataBufferUtils.readInputStream({ r.body() }, DefaultDataBufferFactory.sharedInstance, 64 * 1024)
        return ResponseEntity.status(r.statusCode()).contentType(MediaType.parseMediaType(type))
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .apply {
                // Files are served from the app's own address: pages among them (HTML, SVG) run nothing there.
                // (PDFs and media are left as they are, for the browser's viewers.)
                if (!Regex("^(application/pdf|image/(?!svg)|video/|audio/)").containsMatchIn(type)) header("Content-Security-Policy", "sandbox")
                for (h in listOf(HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_RANGE, HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED)) {
                    r.headers().firstValue(h).ifPresent { header(h, it) }
                }
            }
            .body(body)
    }

    /** A preview of a PDF (its first page as a JPEG, [width] pixels wide); [version] (its modified time) keys caching. */
    @GetMapping("/thumb", produces = [MediaType.IMAGE_JPEG_VALUE])
    suspend fun thumb(@RequestParam path: String, @RequestParam(defaultValue = "") version: String, @RequestParam(defaultValue = "240") width: Int): ResponseEntity<ByteArray> {
        requireEnabled()
        val p = safe(path)
        if (!p.lowercase().endsWith(".pdf")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "only PDFs have previews")
        val jpeg = thumbs.pdf(p, version, width.coerceIn(64, 640)) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control", "private, max-age=604800").body(jpeg)
    }

    /**
     * Writes a file (making its folder); the request body is the contents (may be empty). With
     * If-Match (the ETag it was read at), only if it hasn't changed since: 412 otherwise. The new
     * ETag comes back.
     */
    @PutMapping("/file")
    suspend fun writeFile(
        @RequestParam path: String,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        request: ServerHttpRequest,
    ): ResponseEntity<Unit> {
        requireEnabled()
        val p = safe(path)
        if (p.isEmpty() || p.endsWith("/")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a file path is needed")
        if (courseFile(p)) throw ResponseStatusException(HttpStatus.FORBIDDEN, READ_ONLY)
        dav.makeFolders(p.substringBeforeLast('/', ""))
        // Through a temporary file, not memory (scans and videos can be large).
        val tmp = withContext(Dispatchers.IO) { Files.createTempFile("upload", null) }
        try {
            DataBufferUtils.write(request.body, tmp).awaitSingleOrNull()
            val etag = try {
                withContext(Dispatchers.IO) { Files.newInputStream(tmp) }.use { dav.put(p, it, Files.size(tmp), request.headers.contentType?.toString(), ifMatch?.let(::strong)) }
            } catch (e: WebDav.Changed) {
                throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Changed since it was opened")
            }
            return ResponseEntity.noContent().apply { etag?.let { header(HttpHeaders.ETAG, it) } }.build()
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tmp) }
        }
    }

    /** Makes a folder (and its parents). */
    @PostMapping("/folder")
    suspend fun makeFolder(@RequestParam path: String): ResponseEntity<Unit> {
        requireEnabled()
        dav.makeFolders(editable(path))
        return ResponseEntity.noContent().build()
    }

    /** Moves or renames a file or folder; fails if something is already at [to]. */
    @PostMapping("/move")
    suspend fun moveFile(@RequestParam from: String, @RequestParam to: String): ResponseEntity<Unit> {
        requireEnabled()
        val target = editable(to)
        dav.makeFolders(target.substringBeforeLast('/', ""))
        try {
            dav.move(editable(from), target)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Something is already called that")
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * Copies a file or folder (with what's in it) to [to]; if something is already called that,
     * the copy is numbered ("Notes (2).pdf"). Says where the copy went.
     */
    @PostMapping("/copy")
    suspend fun copyFile(@RequestParam from: String, @RequestParam to: String): DriveCopied {
        requireEnabled()
        val source = safe(from)
        if (source.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "nothing to copy")
        var target = editable(to)
        dav.makeFolders(target.substringBeforeLast('/', ""))
        val dot = target.substringAfterLast('/').lastIndexOf('.').let { if (it <= 0 || from.endsWith("/")) -1 else target.length - target.substringAfterLast('/').length + it }
        val (stem, ext) = if (dot < 0) target to "" else target.substring(0, dot) to target.substring(dot)
        var n = 2
        while (dav.exists(target)) target = "$stem (${n++})$ext"
        dav.copy(source, target)
        return DriveCopied(target)
    }

    /** Deletes a file or folder (with what's in it). */
    @DeleteMapping("/file")
    suspend fun deleteFile(@RequestParam path: String): ResponseEntity<Unit> {
        requireEnabled()
        dav.delete(editable(path))
        return ResponseEntity.noContent().build()
    }

    /**
     * A path that may be changed: not the drive's top, Projects/, or a project's own folder (those
     * follow the projects), and nothing among a course's files from PLMS.
     */
    private fun editable(path: String): String {
        val p = safe(path)
        val depth = p.split('/').size
        if (p.isEmpty() || (p.startsWith("${DriveService.PROJECTS}/") || p == DriveService.PROJECTS) && depth <= 2) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Project folders follow the projects; change the project instead")
        }
        if (courseFile(p)) throw ResponseStatusException(HttpStatus.FORBIDDEN, READ_ONLY)
        return p
    }

    /** In a project's Course files (what PLMS has, kept as it is there), or that folder itself. */
    private fun courseFile(p: String) = p.split('/').let { it.size >= 3 && it[0] == DriveService.PROJECTS && it[2] == DriveService.COURSE_FILES }

    /** An ETag as the drive gave it: a proxy compressing the response on the way marks it weak (W/). */
    private fun strong(etag: String) = etag.split(',').joinToString(", ") { it.trim().removePrefix("W/") }

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
