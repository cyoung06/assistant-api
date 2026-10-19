package com.syeyoung.assistant.source.plms

import com.syeyoung.assistant.drive.DriveService
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.tasks.TaskRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Course files from PLMS (activities' files and assignments' attachments), copied into each course's
 * project folder in the drive, all in Course files/. A file is copied once; if PLMS later
 * has a newer version, it's copied again beside the old one ("name (updated 2026-10-21).pdf"), so a
 * copy that was written on is never replaced.
 */
@Component
class PlmsFiles(
    private val plms: PlmsSource,
    private val drive: DriveService,
    private val dav: WebDav,
    private val projects: TaskRepository,
    private val sources: com.syeyoung.assistant.source.SourceRepository,
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Copies new course files in the background (called after each catalog refresh). */
    fun importLater(courses: List<Pair<Long, String>>) {
        if (!drive.enabled) return
        scope.launch {
            try {
                import(courses)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("PLMS files: {}", e.toString())
            }
        }
    }

    /** Copies what's new for each course; returns how many files were copied. */
    suspend fun import(courses: List<Pair<Long, String>>): Int = lock.withLock {
        val all = projects.projects()
        // The course's project: the one keyed to it, else the one the sync linked by the course's
        // name (a project that existed before PLMS was connected keeps its own source).
        val linked = sources.refs(SourceType.PLMS)
        fun projectOf(id: Long, name: String) = all.find { it.sourceType == SourceType.PLMS.wire && it.sourceKey == "course:$id" }
            ?: linked["${SourceType.PLMS.wire}:project:$name"]?.let { uid -> all.find { it.id.toString() == uid } }
        class Copied(val path: String, val modified: Long)
        val known = db.sql("select key, path, modified from plms_files")
            .map { r -> r.get("key", String::class.java)!! to Copied(r.get("path", String::class.java)!!, r.get("modified", java.lang.Long::class.java)!!.toLong()) }
            .flow().toList().toMap()
        var copied = 0
        for ((course, courseName) in courses) {
            val project = projectOf(course, courseName) ?: continue
            val folder = drive.folderOf(project.id) + DriveService.COURSE_FILES + "/"
            val all = files(plms.client.courseContents(course), folder) + runCatching { assignmentFiles(plms.client.assignments(course)) }.onFailure { log.warn("PLMS assignment files of course {}: {}", course, it.toString()) }.getOrDefault(emptyList())
            val taken = (dav.list(folder)?.map { it.path }.orEmpty()).toMutableSet()
            for (file in all) {
                val previous = known[file.key]
                if (previous != null && previous.modified >= file.modified) {
                    // Copied earlier into a week's folder: brought up into Course files (keeping its name).
                    val flat = folder + file.subfolder + previous.path.substringAfterLast('/')
                    if (previous.path != flat && previous.path.startsWith(folder)) runCatching {
                        val to = free(flat, taken)
                        dav.makeFolders(to.substringBeforeLast('/'))
                        dav.move(previous.path, to)
                        taken += to
                        record(file, to)
                        log.info("PLMS file moved: {} → {}", previous.path, to)
                    }
                    continue
                }
                val name = if (previous == null) file.name else updatedName(file.name, Instant.ofEpochSecond(file.modified))
                val path = free(folder + file.subfolder + name, taken).also { taken += it }
                try {
                    dav.makeFolders(path.substringBeforeLast('/'))
                    plms.client.download(file.url).let { r -> r.body().use { dav.put(path, it, file.size.takeIf { s -> s > 0 }, file.mime) } }
                    record(file, path)
                    copied++
                    log.info("PLMS file copied: {}", path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("PLMS file {} not copied: {}", path, e.toString())
                }
            }
            // Week folders left empty by the move go.
            dav.list(folder)?.filter { it.folder }?.forEach { f -> if (dav.list(f.path)?.isEmpty() == true && f.path in sectionFolders) dav.delete(f.path) }
        }
        copied
    }

    /** [path], or "name (2).ext" and so on if something (another file of the same name) is there. */
    private fun free(path: String, taken: Set<String>): String {
        if (path !in taken) return path
        val dot = path.lastIndexOf('.').takeIf { it > path.lastIndexOf('/') } ?: path.length
        var n = 2
        while (true) { val p = path.substring(0, dot) + " ($n)" + path.substring(dot); if (p !in taken) return p; n++ }
    }

    /** The files attached to the course's assignments (handouts, starter code), in Course files too. */
    private fun assignmentFiles(assignments: JsonNode): List<CourseFile> = assignments.flatMap { a ->
        a.path("introattachments").filter { it.path("fileurl").asString("").isNotBlank() }.map { f ->
            CourseFile(
                key = "assign/${a.path("cmid").asLong()}/${f.path("filepath").asString("/")}${f.path("filename").asString("")}",
                url = f.path("fileurl").asString(""),
                name = DriveService.safeName(f.path("filename").asString("file")),
                subfolder = "",
                size = f.path("filesize").asLong(0),
                modified = f.path("timemodified").asLong(0),
                mime = f.path("mimetype").asString(null),
            )
        }
    }

    private class CourseFile(val key: String, val url: String, val name: String, val subfolder: String, val size: Long, val modified: Long, val mime: String?)

    /** Folders named after the course's sections (where files used to go), to remove once empty. */
    private val sectionFolders = mutableSetOf<String>()

    /**
     * The course's files: each file of its file, resource and folder activities, all straight in
     * Course files/ (a folder activity's files in a folder named after it).
     */
    private fun files(contents: JsonNode, folder: String = ""): List<CourseFile> = contents.flatMap { section ->
        val sectionName = DriveService.safeName(section.path("name").asString(""))
        if (sectionName.isNotBlank()) sectionFolders += "$folder$sectionName/"
        val sub = ""
        section.path("modules").flatMap { module ->
            module.path("contents").filter { it.path("type").asString("") == "file" && it.path("fileurl").asString("").isNotBlank() }.map { f ->
                val inFolder = f.path("filepath").asString("/").trim('/').split('/').filter { it.isNotBlank() }.joinToString("") { DriveService.safeName(it) + "/" }
                // A folder activity's files sit in a folder named after it.
                val activity = if (module.path("modname").asString("") == "folder") DriveService.safeName(module.path("name").asString("Folder")) + "/" else ""
                val name = DriveService.safeName(f.path("filename").asString("file"))
                CourseFile(
                    key = "${module.path("id").asLong()}/${f.path("filepath").asString("/")}${f.path("filename").asString("")}",
                    url = f.path("fileurl").asString(""),
                    name = name,
                    subfolder = sub + activity + inFolder,
                    size = f.path("filesize").asLong(0),
                    modified = f.path("timemodified").asLong(0),
                    mime = f.path("mimetype").asString(null),
                )
            }
        }
    }

    private suspend fun record(f: CourseFile, path: String) {
        db.sql(
            """
            insert into plms_files (key, path, modified, size, copied_at) values (:key, :path, :modified, :size, :now)
            on conflict (key) do update set path = :path, modified = :modified, size = :size, copied_at = :now
            """,
        ).bind("key", f.key).bind("path", path).bind("modified", f.modified).bind("size", f.size).bind("now", Instant.now(clock)).fetch().awaitRowsUpdated()
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        /** "Lecture 3.pdf" updated on a day: "Lecture 3 (updated 2026-10-21).pdf". */
        fun updatedName(name: String, at: Instant, zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
            val day: LocalDate = at.atZone(zone).toLocalDate()
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} (updated $day)${name.substring(dot)}" else "$name (updated $day)"
        }
    }
}
