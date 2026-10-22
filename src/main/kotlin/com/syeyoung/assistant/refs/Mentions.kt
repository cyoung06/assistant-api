package com.syeyoung.assistant.refs

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.drive.DriveIndex
import com.syeyoung.assistant.tasks.TaskRepository
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * What a mention in a note (`@Name`, `@[Name]`) is: the thing called that, looked for in this order:
 * `#12` is task 12; a note or file of the note's own project; an open task (then any); a project; a
 * note or file anywhere (the newest); an event (the next, else the latest). Names match whatever
 * the case; a task's is its title, a note's its name without .md, a file's its name.
 */
@Component
class Mentions(private val tasks: TaskRepository, private val drive: DriveIndex, private val driveRefs: DriveRefs, private val ws: Workspace, props: AppProperties) {
    private val base = props.publicUrl

    /** What [name], mentioned in the note at [from] (if known), is; null if nothing's called that. */
    suspend fun resolve(name: String, from: String?): Ref? {
        val n = name.trim()
        Regex("""^#(\d+)$""").matchEntire(n)?.let { m ->
            val t = tasks.tasks().find { it.number == m.groupValues[1].toLong() } ?: return null
            return Ref(RefKind.TASK, "#${t.number} ${plainText(t.title)}", AppLinks.task(base, t.id), date = t.updatedAt)
        }
        val files = drive.byName(n)
        val project = from?.split('/')?.take(2)?.joinToString("/", postfix = "/")
        files.firstOrNull { project != null && it.path.startsWith(project) }?.let { return driveRefs.ref(it) }
        tasks.tasks().filter { plainText(it.title).equals(n, ignoreCase = true) }
            .sortedWith(compareBy({ it.status.closed }, { -it.number })).firstOrNull()
            ?.let { return Ref(RefKind.TASK, "#${it.number} ${plainText(it.title)}", AppLinks.task(base, it.id), date = it.updatedAt) }
        tasks.projects().find { it.name.equals(n, ignoreCase = true) }?.let { return Ref(RefKind.PROJECT, it.name, AppLinks.project(base, it.id), "project") }
        files.firstOrNull()?.let { return driveRefs.ref(it) }
        val now = Instant.now()
        val events = ws.snapshot().events.filter { plainText(it.title).equals(n, ignoreCase = true) }
        val event = events.filter { (it.start ?: Instant.MIN) >= now }.minByOrNull { it.start!! } ?: events.maxByOrNull { it.start ?: Instant.MIN }
        return event?.let { Ref(RefKind.EVENT, plainText(it.title), AppLinks.event(base, it.uid), date = it.start) }
    }

    /** The names [ref] could be mentioned by: a task's title and #number, a note's or file's name, a project's or event's. */
    fun namesOf(ref: Ref): List<String> = when (ref.kind) {
        RefKind.TASK -> Regex("""^(#\d+) (.*)$""").matchEntire(ref.title)?.let { listOf(it.groupValues[1], it.groupValues[2]) } ?: listOf(ref.title)
        else -> listOf(ref.title)
    }.map { it.lowercase() }.distinct()
}
