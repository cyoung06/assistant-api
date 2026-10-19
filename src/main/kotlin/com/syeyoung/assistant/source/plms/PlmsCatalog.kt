package com.syeyoung.assistant.source.plms

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.refs.PlmsLinks
import com.syeyoung.assistant.refs.Ref
import com.syeyoung.assistant.refs.RefIndex
import com.syeyoung.assistant.refs.RefKind
import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.tasks.TaskSourceSync
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What can be referenced in PLMS, and a project per course: each course in progress, its files, boards and assignments, and
 * the recent posts on its boards (notices, Q&A). Read from the course and board pages, since
 * PLMS's Moodle doesn't offer course contents to the web service; refreshed every few hours.
 */
@Component
class PlmsCatalog(
    private val plms: PlmsSource,
    private val index: RefIndex,
    private val projects: TaskSourceSync,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = props.sources.plms.baseUrl.trimEnd('/')

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.sources.schedulerEnabled || !plms.enabled) return
        scope.launch {
            delay(props.sources.initialDelay.toMillis() * 2)
            while (isActive) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("PLMS catalog refresh failed: {}", e.toString())
                }
                delay(props.sources.plms.catalogInterval.toMillis())
            }
        }
    }

    suspend fun refresh(): Int {
        val refs = mutableListOf<Ref>()
        val courses = plms.client.courses()
        // A project per course this semester; ended courses' projects are completed.
        val tags = courses.map {
            SourceTag(
                "course:${it.path("id").asLong()}", PlmsSource.courseName(it.path("fullname").asString("")),
                it.path("startdate").asLong(0).takeIf { s -> s > 0 }?.let { s -> PlmsSource.semester(Instant.ofEpochSecond(s).atZone(props.zone).toLocalDate()) },
            )
        }
        projects.syncProjects(SourceType.PLMS, tags, Instant.now(clock))
        for (course in courses) {
            val id = course.path("id").asLong()
            val name = PlmsSource.courseName(course.path("fullname").asString(""))
            val courseUrl = "$base/course/view.php?id=$id"
            refs += Ref(RefKind.PLMS_COURSE, name, PlmsLinks.fromWeb(courseUrl, base), "course")
            val activities = activities(plms.client.page(courseUrl), name)
            refs += activities
            for (board in activities.filter { it.url.startsWith("plms:mod/ubboard/") }) {
                refs += posts(plms.client.page(PlmsLinks.toWeb(board.url, base)!!), "$name · ${board.title}")
            }
        }
        index.replace(SOURCE, refs, Instant.now(clock))
        log.info("PLMS catalog: {} references", refs.size)
        return refs.size
    }

    /** The course page's activities: files, boards, assignments and the rest (labels are only text). */
    fun activities(html: String, course: String): List<Ref> = ACTIVITY.findAll(html).mapNotNull { m ->
        val (mod, cmid, rawName) = m.destructured
        if (mod == "label") return@mapNotNull null
        val title = text(rawName).ifBlank { return@mapNotNull null }
        val kind = when (mod) {
            "ubfile", "resource" -> RefKind.PLMS_FILE
            "assign" -> RefKind.PLMS_ASSIGNMENT
            else -> RefKind.PLMS_ACTIVITY
        }
        Ref(kind, title, "plms:mod/$mod/$cmid", course)
    }.distinctBy { it.url }.toList()

    /** The first page of a board: its most recent posts. */
    fun posts(html: String, context: String): List<Ref> = POST_ROW.findAll(html).mapNotNull { row ->
        val link = POST_LINK.find(row.value) ?: return@mapNotNull null
        val href = link.groupValues[1].replace("&amp;", "&")
        val bwid = Regex("""[?&]bwid=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val cmid = Regex("""[?&]id=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val date = POST_DATE.find(row.value)?.groupValues?.get(1)?.let { runCatching { LocalDateTime.parse(it, STAMP).atZone(ZONE).toInstant() }.getOrNull() }
        // Badges ("N" for new) aren't part of the title.
        val title = text(link.groupValues[2].replace(BADGE, ""))
        Ref(RefKind.PLMS_NOTICE, title, "plms:post/$cmid/$bwid", context, date)
    }.distinctBy { it.url }.toList()

    private fun canonicalPost(cmid: String, bwid: String) = "$base/mod/ubboard/article.php?id=$cmid&bwid=$bwid"

    /** The form a pasted PLMS link is indexed under (board post links carry paging parameters). */
    fun canonical(url: String): String {
        if (!url.startsWith(base)) return url
        if ("/mod/ubboard/article.php" in url) {
            val bwid = Regex("""[?&]bwid=(\d+)""").find(url)?.groupValues?.get(1)
            val cmid = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1)
            if (bwid != null && cmid != null) return canonicalPost(cmid, bwid)
        }
        return Regex("""^(.*?/(?:mod/\w+/view|course/view)\.php\?id=\d+).*$""").find(url)?.groupValues?.get(1) ?: url
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        const val SOURCE = "plms"
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val ACTIVITY = Regex(
            // Stay within one activity: labels have no name, and must not take the next one's.
            """<li class="activity (\w+) modtype_\w+[^"]*" id="module-(\d+)"(?:(?!<li class="activity ).)*?<span class="instancename">(.*?)(?:<span class="accesshide|</span>)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val POST_ROW = Regex("""<tr[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        private val POST_LINK = Regex("""<a href="([^"]*?/mod/ubboard/article\.php\?[^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val BADGE = Regex("""<span class="badge[^"]*">.*?</span>""", RegexOption.DOT_MATCHES_ALL)
        private val POST_DATE = Regex("""<span title="(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})">""")

        /** Visible text of an HTML fragment. */
        fun text(html: String) = html.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ").trim()
    }
}
