package com.syeyoung.assistant.refs

import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.config.OpenWeb
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.mail.MailRefs
import com.syeyoung.assistant.source.plms.PlmsCatalog
import com.syeyoung.assistant.source.plms.PlmsSource
import com.syeyoung.assistant.tasks.TaskRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Mono
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Finds things to reference (@), names pasted links, and lists what references something. */
@Service
class RefService(
    private val tasks: TaskRefs,
    private val events: EventRefs,
    private val drive: DriveRefs,
    private val driveIndex: com.syeyoung.assistant.drive.DriveIndex,
    private val index: RefIndex,
    private val mail: MailRefs,
    private val github: GitHubRefs,
    private val plms: PlmsSource,
    private val catalog: PlmsCatalog,
    private val taskRepo: TaskRepository,
    private val ws: Workspace,
    private val web: OpenWeb,
    props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = props.publicUrl
    private val plmsBase = props.sources.plms.baseUrl
    private val plmsHost = URI(plmsBase).host

    /** Everything matching [query], from every provider at once; mail gets a few seconds at most. */
    suspend fun search(query: String, limit: Int): List<Ref> = coroutineScope {
        val providers = listOf(tasks to 2_000L, events to 2_000L, drive to 2_000L, index to 2_000L, mail to 4_000L, github to 3_000L)
        val results = providers.map { (p, timeout) ->
            async {
                withTimeoutOrNull(timeout) { runCatching { p.search(query, limit) }.onFailure { log.debug("ref search failed: {}", it.toString()) }.getOrNull() }.orEmpty()
            }
        }.awaitAll().flatten()
        val q = query.trim().lowercase()
        // Titles starting with the query first, then the order providers gave.
        results.withIndex().sortedWith(compareBy({ !it.value.title.lowercase().removePrefix("#").startsWith(q.removePrefix("#")) }, { it.index }))
            .map { it.value }.distinctBy { it.url }.take(limit)
    }

    /** The thing behind a pasted link: one of ours, a mail, a known PLMS page, or any web page by its title. */
    suspend fun resolve(url: String): Ref {
        val pasted = url.trim()
        if (pasted.startsWith("mid:")) return mail.resolve(pasted) ?: Ref(RefKind.MAIL, "Mail", pasted)
        if (pasted.startsWith("plms:")) return index.resolve(pasted) ?: PlmsLinks.toWeb(pasted, plmsBase)?.let { resolve(it) } ?: throw ValidationException("Not a PLMS link")
        val uri = runCatching { URI(pasted) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") && it.host != null }
            ?: throw ValidationException("Not a web link")
        val link = uri.toString()
        AppLinks.parse(link)?.let { target ->
            val provider: RefProvider = when (target.kind) {
                RefKind.MAIL -> mail
                RefKind.EVENT -> events
                RefKind.NOTE, RefKind.FILE -> drive
                else -> tasks
            }
            provider.resolve(link)?.let { return it }
        }
        if (uri.host == "github.com") github.resolve(link)?.let { return it }
        if (uri.host == plmsHost) {
            val canonical = catalog.canonical(link)
            val plmsLink = PlmsLinks.fromWeb(canonical, plmsBase)
            index.resolve(plmsLink)?.let { return it }
            if (plms.enabled) {
                val title = runCatching { titleOf(plms.client.page(canonical)) }.getOrNull()
                return Ref(RefKind.PLMS_ACTIVITY, title?.removePrefix("강좌: ")?.removePrefix("Course: ") ?: "PLMS page", plmsLink, "PLMS")
            }
        }
        return Ref(RefKind.LINK, unfurl(uri) ?: uri.host + uri.path.takeIf { it.length > 1 }.orEmpty(), link, uri.host)
    }

    /** Tasks and events whose title, description or notes link to [url], and notes in the drive that do. */
    suspend fun backlinks(url: String): List<Ref> {
        val target = AppLinks.parse(url)
        // Our own links are matched by their path (or old #fragment), so a different host in the text still counts.
        val needles = when {
            target == null || url.startsWith("mid:") -> listOf(url)
            else -> url.substringAfter("://").substringAfter('/').removePrefix("#").let { path -> listOf("/$path", "#$path") }
        }
        val linked = { text: String -> needles.any { it in text } }
        val fromTasks = taskRepo.tasks().filter { (linked(it.title) || linked(it.description)) && !(target?.kind == RefKind.TASK && it.id.toString() == target.id) }
            .map { Ref(RefKind.TASK, "#${it.number} ${plainText(it.title)}", AppLinks.task(base, it.id), date = it.updatedAt) }
        val fromEvents = ws.snapshot().events.filter { linked(it.title) || linked(it.notes) }
            .map { Ref(RefKind.EVENT, plainText(it.title), AppLinks.event(base, it.uid), date = it.start) }
        val fromNotes = driveIndex.notesLinkingTo(url).map(drive::ref)
        return fromTasks + fromEvents + fromNotes
    }

    suspend fun refreshCatalog(): Int = catalog.refresh()

    /** A web page's og:title or <title> (from its first 256 KB); only public addresses are fetched. */
    private suspend fun unfurl(uri: URI): String? = runCatching {
        web.client.get().uri(uri).header("User-Agent", "Mozilla/5.0 (assistant link preview)")
            .exchangeToMono { r ->
                if (!r.statusCode().is2xxSuccessful) r.releaseBody().then(Mono.empty())
                else DataBufferUtils.join(DataBufferUtils.takeUntilByteCount(r.bodyToFlux(DataBuffer::class.java), 256L * 1024))
                    .map { buf -> try { buf.toString(Charsets.UTF_8) } finally { DataBufferUtils.release(buf) } }
            }
            .awaitSingleOrNull()
            ?.let(::titleOf)
    }.onFailure { log.debug("Link preview {}: {}", uri, it.toString()) }.getOrNull()

    private fun titleOf(html: String): String? {
        val og = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val title = og ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
        return title?.let(PlmsCatalog::text)?.takeIf { it.isNotBlank() }
    }
}

@RestController
@RequestMapping("/api/v1/refs")
class RefController(private val service: RefService) {

    /** Things to reference that match [q]: tasks, projects, events, PLMS files and notices, mail. */
    @GetMapping
    suspend fun search(@RequestParam(defaultValue = "") q: String, @RequestParam(defaultValue = "12") limit: Int): List<Ref> =
        service.search(q, limit.coerceIn(1, 50))

    /** Names a pasted link. */
    @GetMapping("/resolve")
    suspend fun resolve(@RequestParam url: String): Ref = service.resolve(url)

    /** What links to [url]. */
    @GetMapping("/backlinks")
    suspend fun backlinks(@RequestParam url: String): List<Ref> = service.backlinks(url)

    /** Lists PLMS course files and board posts now instead of at the next scheduled refresh. */
    @PostMapping("/plms/refresh")
    suspend fun refreshPlms(): Map<String, Int> = mapOf("references" to service.refreshCatalog())
}
