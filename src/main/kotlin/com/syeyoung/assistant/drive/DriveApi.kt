package com.syeyoung.assistant.drive

import com.syeyoung.assistant.common.servedSafely
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.sync.SyncDocs
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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

data class DriveRestored(val path: String)

private const val READ_ONLY = "Course files are kept as PLMS has them; copy one out to change it"

/** The drive: what's in a project's folder, and reading and writing its files. */
@RestController
@RequestMapping("/api/v1/drive")
class DriveController(private val drive: DriveService, private val dav: WebDav, private val thumbs: DriveThumbs, private val props: AppProperties, private val live: SyncDocs, private val trash: DriveTrash, private val state: DriveFileState) {

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
        if (folder.startsWith(DriveTrash.TRASH + "/")) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val entries = dav.list(folder) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        // The trash is listed by itself (see /trash), not as a folder.
        return DriveFolder(folder, entries.filter { it.path != DriveTrash.TRASH + "/" })
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
        // Streamed straight through from the drive.
        val r = dav.get(safe(path), range, ifNoneMatch?.let(::strong)) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val type = r.headers.contentType ?: MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.status(r.statusCode).contentType(type)
            .servedSafely(type.toString())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .apply {
                for (h in listOf(HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_RANGE, HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED)) {
                    r.headers.getFirst(h)?.let { header(h, it) }
                }
            }
            .body(r.body ?: Flux.empty())
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
     * If-Match (the ETag it was read at), only if it hasn't changed since; with `If-None-Match: *`,
     * only if there's no file there yet: 412 otherwise. The new ETag comes back.
     */
    @PutMapping("/file")
    suspend fun writeFile(
        @RequestParam path: String,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        request: ServerHttpRequest,
    ): ResponseEntity<Unit> {
        requireEnabled()
        if (ifNoneMatch != null && ifNoneMatch.trim() != "*") throw ResponseStatusException(HttpStatus.BAD_REQUEST, "If-None-Match on a write can only be *")
        val p = safe(path)
        if (p.isEmpty() || p.endsWith("/")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a file path is needed")
        if (DriveService.isCourseFile(p)) throw ResponseStatusException(HttpStatus.FORBIDDEN, READ_ONLY)
        dav.makeFolders(p.substringBeforeLast('/', ""))
        // Streamed straight to the drive (scans and videos can be large); its length, if the client said.
        val etag = try {
            dav.put(p, request.body, request.headers.contentLength.takeIf { it >= 0 }, request.headers.contentType?.toString(), ifMatch?.let(::strong), absent = ifNoneMatch != null)
        } catch (e: WebDav.Changed) {
            throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED, if (ifNoneMatch != null) "There's a file there already" else "Changed since it was opened")
        }
        // A note open live: the devices on it see this write now.
        live.written(p)
        return ResponseEntity.noContent().apply { etag?.let { header(HttpHeaders.ETAG, it) } }.build()
    }

    /** Makes a folder (and its parents). */
    @PostMapping("/folder")
    suspend fun makeFolder(@RequestParam path: String): ResponseEntity<Unit> {
        requireEnabled()
        dav.makeFolders(editable(path, folder = true))
        return ResponseEntity.noContent().build()
    }

    /** Moves or renames a file or folder; fails if something is already at [to]. */
    @PostMapping("/move")
    suspend fun moveFile(@RequestParam from: String, @RequestParam to: String): ResponseEntity<Unit> {
        requireEnabled()
        val source = editable(from)
        val target = editable(to, folder = dav.isFolder(source))
        dav.makeFolders(target.substringBeforeLast('/', ""))
        // Notes open there are written first, and their devices let go.
        live.release(source)
        try {
            dav.move(source, target)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Something is already called that")
        }
        live.moved(source, target)
        state.moved(source, target)
        return ResponseEntity.noContent().build()
    }

    // ---- the person's state of files: starred, recently opened, where a PDF was left ----

    /** What's kept about [path]: starred, when last opened, where a PDF was left. */
    @GetMapping("/state")
    suspend fun fileState(@RequestParam path: String): FileState = state.get(safePath(path))

    /** Changes what's kept about [path] (what's given is set, the rest left as it is). */
    @PatchMapping("/state")
    suspend fun changeFileState(@RequestParam path: String, @RequestBody change: FileStateChange): FileState = state.change(safePath(path), change)

    /** The files opened most recently, latest first. */
    @GetMapping("/recent")
    suspend fun recentFiles(@RequestParam(defaultValue = "20") limit: Int): List<FileState> = state.recent(limit.coerceIn(1, 100))

    /** The starred files and folders. */
    @GetMapping("/starred")
    suspend fun starredFiles(): List<FileState> = state.starred()

    /** A drive path as files' states keep it: a folder's with its "/". */
    private fun safePath(path: String) = safe(path).let { if (path.endsWith("/") && it.isNotEmpty()) "$it/" else it }

    /**
     * Copies a file or folder (with what's in it) to [to]; if something is already called that,
     * the copy is numbered ("Notes (2).pdf"). Says where the copy went.
     */
    @PostMapping("/copy")
    suspend fun copyFile(@RequestParam from: String, @RequestParam to: String): DriveCopied {
        requireEnabled()
        val source = safe(from)
        if (source.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "nothing to copy")
        var target = editable(to, folder = dav.isFolder(source))
        dav.makeFolders(target.substringBeforeLast('/', ""))
        val dot = target.substringAfterLast('/').lastIndexOf('.').let { if (it <= 0 || from.endsWith("/")) -1 else target.length - target.substringAfterLast('/').length + it }
        val (stem, ext) = if (dot < 0) target to "" else target.substring(0, dot) to target.substring(dot)
        var n = 2
        while (dav.exists(target)) target = "$stem (${n++})$ext"
        live.flush(source)
        dav.copy(source, target)
        return DriveCopied(target)
    }

    /** Deletes a file or folder (with what's in it): to the trash, kept 30 days (see [DriveTrash]). */
    @DeleteMapping("/file")
    suspend fun deleteFile(@RequestParam path: String): ResponseEntity<Unit> {
        requireEnabled()
        val p = editable(path)
        if (!dav.exists(p) && !dav.isFolder(p)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        trash.trash(p)
        return ResponseEntity.noContent().build()
    }

    /** What's in the trash, latest first. */
    @GetMapping("/trash")
    suspend fun listTrash(): List<Trashed> {
        requireEnabled()
        return trash.list()
    }

    /** Puts something back where it was (numbered if that name's taken now); where it went. */
    @PostMapping("/trash/{id}/restore")
    suspend fun restoreTrashed(@PathVariable id: UUID): DriveRestored {
        requireEnabled()
        return DriveRestored(trash.restore(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND))
    }

    /** Deletes one thing from the trash for good. */
    @DeleteMapping("/trash/{id}")
    suspend fun purgeTrashed(@PathVariable id: UUID): ResponseEntity<Unit> {
        requireEnabled()
        if (!trash.purge(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.noContent().build()
    }

    /** Empties the trash: everything in it deleted for good. */
    @DeleteMapping("/trash")
    suspend fun emptyTrash(): ResponseEntity<Unit> {
        requireEnabled()
        for (item in trash.list()) trash.purge(item.id)
        return ResponseEntity.noContent().build()
    }

    /**
     * A path that may be changed: not the drive's top, Projects/, or a project's own folder (those
     * follow the projects), and nothing among a course's files from PLMS. Files beside the project
     * folders may be: [folder] is whether it's a folder that's being made or put there (else
     * whether one is there).
     */
    private suspend fun editable(path: String, folder: Boolean? = null): String {
        val p = safe(path)
        val parts = p.split('/')
        val projectLevel = parts[0] == DriveService.PROJECTS && parts.size <= 2
        if (p.isEmpty() || projectLevel && (parts.size == 1 || folder ?: dav.isFolder(p))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Project folders follow the projects; change the project instead")
        }
        if (DriveService.isCourseFile(p)) throw ResponseStatusException(HttpStatus.FORBIDDEN, READ_ONLY)
        if (p == DriveTrash.TRASH || p.startsWith(DriveTrash.TRASH + "/")) throw ResponseStatusException(HttpStatus.FORBIDDEN, "The trash is changed through /trash")
        return p
    }


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
