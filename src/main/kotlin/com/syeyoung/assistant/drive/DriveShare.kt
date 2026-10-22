package com.syeyoung.assistant.drive

import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.tasks.AttachmentRepository
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * What was shared to the app, put in a project (none: No project): as [kind] "file", the shared
 * files ([attachmentIds], uploaded first) into its folder; as "note", a note in its Notes with the
 * [title], the [text] and the files beside it (images shown in it, others linked).
 */
data class ShareRequest(val kind: String = "note", val projectId: UUID? = null, val title: String? = null, val text: String = "", val attachmentIds: List<UUID> = emptyList())

/** Where what was shared went (the note first, for a note). */
data class Shared(val paths: List<String>)

@Component
class DriveShare(private val dav: WebDav, private val drive: DriveService, private val attachments: AttachmentRepository, private val props: AppProperties, private val clock: Clock) {

    suspend fun share(r: ShareRequest): Shared {
        check(drive.enabled) { "The drive isn't set up" }
        return shareInto(r.projectId?.let { drive.folderOf(it) } ?: "${DriveService.PROJECTS}/${DriveService.NO_PROJECT}/", r)
    }

    /** [r] put in the project folder [folder]. */
    internal suspend fun shareInto(folder: String, r: ShareRequest): Shared {
        val files = r.attachmentIds.map { attachments.get(it) ?: throw ValidationException("No such upload: $it") }
        // Files with nothing to say about them go in as they are; anything else (text, a link) is a note.
        if (r.kind == "file" && files.isNotEmpty()) return Shared(files.map { put(folder, it.info.filename, it.bytes, it.info.contentType) })

        val notes = folder + "Notes/"
        val now = OffsetDateTime.now(clock.withZone(props.zone))
        val beside = files.map { f ->
            val path = put(notes + "attachments/", f.info.filename, f.bytes, f.info.contentType)
            val name = path.substringAfterLast('/')
            val link = "attachments/" + name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
            path to if (f.info.contentType.startsWith("image/")) "![$name]($link)" else "[$name]($link)"
        }
        val title = r.title?.trim()?.ifEmpty { null } ?: r.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "Shared"
        val body = buildString {
            append("# ").append(title).append("\n\n")
            if (r.text.isNotBlank()) append(r.text.trim()).append("\n\n")
            beside.forEach { append(it.second).append("\n\n") }
        }
        val note = put(notes, now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")) + ".md", body.toByteArray(), "text/markdown; charset=utf-8")
        return Shared(listOf(note) + beside.map { it.first })
    }

    /** [bytes] written as [name] in [folder] (numbered if that's taken); where it went. */
    private suspend fun put(folder: String, name: String, bytes: ByteArray, type: String): String {
        dav.makeFolders(folder)
        val clean = DriveService.safeName(name).ifEmpty { "file" }
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        var path = folder + clean
        var n = 2
        while (dav.exists(path)) path = folder + clean.substring(0, dot) + " (${n++})" + clean.substring(dot)
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), bytes.size.toLong(), type, absent = true)
        return path
    }
}

@RestController
@RequestMapping("/api/v1/drive")
class DriveShareController(private val share: DriveShare) {
    /** Puts what was shared into a project: its files into its folder, or a note in its Notes (see [ShareRequest]). */
    @PostMapping("/share")
    suspend fun share(@RequestBody request: ShareRequest): Shared = share.share(request)
}
