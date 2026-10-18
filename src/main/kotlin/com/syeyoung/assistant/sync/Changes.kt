package com.syeyoung.assistant.sync

import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.item.ItemRepository
import com.syeyoung.assistant.item.ItemResponse
import com.syeyoung.assistant.item.ItemViews
import com.syeyoung.assistant.series.SeriesRepository
import com.syeyoung.assistant.series.SeriesResponse
import com.syeyoung.assistant.series.toResponse
import com.syeyoung.assistant.tag.TagRepository
import com.syeyoung.assistant.tag.TagResponse
import com.syeyoung.assistant.tag.toResponse
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ChangesPage(
    /** Changed items, including deleted ones (with `deletedAt` set). */
    val items: List<ItemResponse>,
    val tags: List<TagResponse>,
    /** Changed repeating-event series, including deleted ones. */
    val series: List<SeriesResponse>,
    /** Pass as `since` on the next call. */
    val cursor: Long,
    val hasMore: Boolean,
)

/**
 * Incremental sync. Every write gives the row a new value from `change_seq`; a client remembers
 * the last cursor it saw and asks for everything after it.
 *
 * Caveat: sequence values are taken at write time, not commit time, so a slow transaction can
 * commit a lower value after a client already read past it. With one user and short transactions
 * this is rare; a client that wants to be safe can re-read from a slightly older cursor.
 */
@Service
class ChangesService(
    private val db: DatabaseClient,
    private val items: ItemRepository,
    private val tags: TagRepository,
    private val views: ItemViews,
    private val seriesRepo: SeriesRepository,
) {
    suspend fun since(since: Long, limit: Int): ChangesPage {
        if (since < 0) throw ValidationException("since must be >= 0")
        if (limit !in 1..1000) throw ValidationException("limit must be between 1 and 1000")

        val changed = db.sql(
            """
            select seq, 'item' as kind, id from items where seq > :since
            union all
            select seq, 'tag' as kind, id from tags where seq > :since
            union all
            select seq, 'series' as kind, id from series where seq > :since
            order by seq
            limit :limit
            """,
        ).bind("since", since).bind("limit", limit + 1)
            .map { r -> Triple(r.req<Long>("seq"), r.req<String>("kind"), r.req<UUID>("id")) }
            .flow().toList()

        val page = changed.take(limit)
        val itemIds = page.filter { it.second == "item" }.map { it.third }
        val tagIds = page.filter { it.second == "tag" }.map { it.third }
        val seriesIds = page.filter { it.second == "series" }.map { it.third }
        return ChangesPage(
            items = views.render(items.findAllIncludingDeleted(itemIds).sortedBy { it.seq }),
            tags = tags.findAllIncludingDeleted(tagIds).sortedBy { it.seq }.map { it.toResponse() },
            series = seriesRepo.findAllIncludingDeleted(seriesIds).sortedBy { it.seq }.map { it.toResponse() },
            cursor = page.lastOrNull()?.first ?: since,
            hasMore = changed.size > limit,
        )
    }
}

@RestController
@RequestMapping("/api/v1/changes")
class ChangesController(private val service: ChangesService) {
    /** Everything that changed after cursor [since]. Start with `since=0` for a full download. */
    @GetMapping
    suspend fun changes(
        @RequestParam(defaultValue = "0") since: Long,
        @RequestParam(defaultValue = "500") limit: Int,
    ): ChangesPage = service.since(since, limit)
}
