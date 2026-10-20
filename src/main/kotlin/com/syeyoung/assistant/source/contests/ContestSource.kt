package com.syeyoung.assistant.source.contests

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.SourceType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Upcoming programming contests as events in a "Contests" calendar: Codeforces from its API
 * (contest.list), AtCoder from its contest page (it has no API). Contests that started stay on the
 * calendar; a rescheduled one moves.
 */
@Component
class ContestSource(props: AppProperties, private val mapper: ObjectMapper, web: WebClient.Builder) : DataSource {
    private val config = props.sources.contests

    override val type = SourceType.CONTESTS
    override val displayName = "Contests"
    override val enabled = config.enabled
    override val interval: Duration = config.interval

    private val http = web.defaultHeader("User-Agent", "Mozilla/5.0 (assistant contest calendar)").build()

    override suspend fun fetch(now: Instant): SourceSnapshot {
        val codeforces = runCatching { codeforces(get("https://codeforces.com/api/contest.list?gym=false"), config.codeforces) }
        val atcoder = runCatching { atcoder(get("https://atcoder.jp/contests/?lang=en"), config.atcoder) }
        // One site down still updates the other; both down is an error.
        if (codeforces.isFailure && atcoder.isFailure) throw codeforces.exceptionOrNull()!!
        return SourceSnapshot(codeforces.getOrDefault(emptyList()) + atcoder.getOrDefault(emptyList()))
    }

    /** Codeforces rounds not yet started whose name contains one of [kinds]. */
    fun codeforces(json: String, kinds: List<String>): List<SourceRecord> =
        mapper.readTree(json).path("result").filter { c -> c.path("phase").asString("") == "BEFORE" }.mapNotNull { c ->
            val name = c.path("name").asString("")
            if (kinds.none { it in name }) return@mapNotNull null
            val start = Instant.ofEpochSecond(c.path("startTimeSeconds").asLong())
            val id = c.path("id").asLong()
            event("codeforces:$id", name, start, start.plusSeconds(c.path("durationSeconds").asLong()), "https://codeforces.com/contest/$id", "Codeforces")
        }

    /** AtCoder's "Upcoming Contests" whose ID starts with one of [kinds] (abc, arc, agc, …). */
    fun atcoder(html: String, kinds: List<String>): List<SourceRecord> {
        val upcoming = html.substringAfter("contest-table-upcoming", "").substringBefore("</table>")
        return ROW.findAll(upcoming).mapNotNull { row ->
            val r = row.groupValues[1]
            val start = TIME.find(r)?.groupValues?.get(1)?.let { OffsetDateTime.parse(it, STAMP).toInstant() } ?: return@mapNotNull null
            val (id, rawName) = LINK.find(r)?.destructured ?: return@mapNotNull null
            if (kinds.none { id.startsWith(it) }) return@mapNotNull null
            val (h, m) = DURATION.find(r.substringAfter(rawName))?.destructured ?: return@mapNotNull null
            val name = rawName.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()
            event("atcoder:$id", name, start, start.plusSeconds(h.toLong() * 3600 + m.toLong() * 60), "https://atcoder.jp/contests/$id", "AtCoder")
        }.toList()
    }

    private fun event(ref: String, name: String, start: Instant, end: Instant, url: String, site: String) = SourceRecord(
        ref = ref, title = name, atStart = start, atEnd = end, url = url, notes = url,
        tags = listOf(SourceTag(site.lowercase(), site)), calendar = SourceTag("contests", "Contests"),
    )

    private suspend fun get(url: String): String = http.get().uri(URI(url)).retrieve().awaitBody()

    companion object {
        private val ROW = Regex("""(?s)<tr>(.*?)</tr>""")
        private val TIME = Regex("""<time[^>]*>([^<]+)</time>""")
        private val LINK = Regex("""<a href="/contests/([\w-]+)">(.*?)</a>""")
        private val DURATION = Regex("""<td class="text-center">(\d+):(\d{2})</td>""")
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")
    }
}
