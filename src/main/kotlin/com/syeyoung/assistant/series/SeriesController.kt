package com.syeyoung.assistant.series

import com.syeyoung.assistant.common.parseIfMatch
import com.syeyoung.assistant.common.withETag
import com.syeyoung.assistant.config.AppProperties
import jakarta.annotation.PreDestroy
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
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/series")
class SeriesController(private val service: SeriesService) {

    @GetMapping("/{id}")
    suspend fun get(@PathVariable id: UUID): ResponseEntity<SeriesResponse> =
        service.get(id).let { withETag(it.version, it.toResponse()) }

    /**
     * Changes how the series repeats. Future occurrences that no longer fit are deleted, unless they
     * were changed on their own or are done; new dates get occurrences.
     */
    @PutMapping("/{id}/repeat")
    suspend fun changeRule(
        @PathVariable id: UUID,
        @RequestBody rule: RepeatRule,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<SeriesResponse> =
        service.changeRule(id, rule, parseIfMatch(ifMatch)).let { withETag(it.version, it.toResponse()) }
}

/** Keeps repeating events generated ahead as days pass. */
@Component
class SeriesExtender(private val service: SeriesService, private val props: AppProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.series.schedulerEnabled) return
        scope.launch {
            while (isActive) {
                runCatching { service.extendAll() }
                    .onSuccess { if (it > 0) log.info("Extended {} repeating events", it) }
                    .onFailure { log.warn("Extending repeating events failed: {}", it.toString()) }
                delay(props.series.extendEvery.toMillis())
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()
}
