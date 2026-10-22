package com.syeyoung.assistant.sync

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Which document a path's live state is: a device's copy is used only while it's still that one. */
data class SyncEpoch(val epoch: UUID?)

@RestController
@RequestMapping("/api/v1/sync")
class SyncController(private val docs: SyncDocs) {
    /** Which document [doc]'s live state is now; null if it has none (a device's copy of it is stale). */
    @GetMapping("/epoch")
    suspend fun epoch(@RequestParam doc: String): SyncEpoch = SyncEpoch(docs.epoch(doc))
}
