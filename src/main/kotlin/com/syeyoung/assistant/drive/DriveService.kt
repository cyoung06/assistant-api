package com.syeyoung.assistant.drive

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.tasks.ProjectRow
import com.syeyoung.assistant.tasks.TaskRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Project folders in the drive: Projects/<project name>/ for every project (and Projects/No project/
 * for notebooks that belong to none), kept in step with the projects: a renamed project's folder is
 * moved, a new project gets one. Folders are never deleted here; a deleted project's folder stays.
 */
@Service
class DriveService(private val dav: WebDav, private val projects: TaskRepository, private val db: DatabaseClient, private val props: AppProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    val enabled get() = props.drive.enabled

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) return
        scope.launch {
            while (isActive) {
                try {
                    syncFolders()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Project folders: {}", e.toString())
                }
                delay(props.drive.folderInterval.toMillis())
            }
        }
    }

    /** Every project's folder exists where its name says. */
    suspend fun syncFolders() = lock.withLock {
        dav.makeFolders("$PROJECTS/$NO_PROJECT/")
        val stored = db.sql("select project_id, path from project_folders").map { r -> r.get("project_id", UUID::class.java)!! to r.get("path", String::class.java)!! }.flow().toList().toMap()
        val taken = stored.values.toMutableSet()
        for (p in projects.projects()) {
            val current = stored[p.id]
            val wanted = folderName(p, taken - setOfNotNull(current))
            when {
                current == wanted -> continue
                current != null && dav.exists(current) -> { dav.move(current, wanted); log.info("Moved {} to {}", current, wanted) }
                else -> dav.makeFolders(wanted)
            }
            taken -= current ?: ""
            taken += wanted
            save(p.id, wanted)
        }
    }

    /** The project's folder (made if it isn't there yet). */
    suspend fun folderOf(project: UUID): String {
        stored(project)?.let { return it }
        syncFolders()
        return stored(project) ?: error("project $project has no folder")
    }

    private suspend fun stored(project: UUID): String? =
        db.sql("select path from project_folders where project_id = :id").bind("id", project).map { r -> r.get("path", String::class.java)!! }.flow().toList().firstOrNull()

    private suspend fun save(project: UUID, path: String) {
        db.sql("insert into project_folders (project_id, path) values (:id, :path) on conflict (project_id) do update set path = :path")
            .bind("id", project).bind("path", path).fetch().awaitRowsUpdated()
    }

    /** Projects/<name>/, the name made safe for a folder, and unique. */
    private fun folderName(p: ProjectRow, taken: Set<String>): String {
        val base = safeName(p.name).ifBlank { "Project" }
        var name = "$PROJECTS/$base/"
        var n = 2
        while (name in taken) name = "$PROJECTS/$base (${n++})/"
        return name
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        const val PROJECTS = "Projects"
        const val NO_PROJECT = "No project"
        /** Each course project's files from PLMS: kept as PLMS has them (read-only here; copy one to write on it). */
        const val COURSE_FILES = "Course files"

        /** A name usable as a file or folder name on any system. */
        fun safeName(name: String) = name.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().trimEnd('.')
    }
}
