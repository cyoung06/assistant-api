package com.syeyoung.assistant.source

import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.item.SourceType
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Syncs every enabled source on its interval, starting shortly after the app is up. */
@Component
class SourceScheduler(private val sync: SourceSync, private val props: AppProperties) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.sources.schedulerEnabled) return
        for (source in sync.all().filter { it.enabled }) {
            scope.launch {
                delay(props.sources.initialDelay.toMillis())
                while (isActive) {
                    sync.run(source)
                    delay(source.interval.toMillis())
                }
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()
}

@RestController
@RequestMapping("/api/v1/sources")
class SourceController(private val sync: SourceSync) {

    @GetMapping
    suspend fun list(): List<SourceStatus> = sync.all().map { sync.status(it) }

    /** Syncs now and returns the resulting status. */
    @PostMapping("/{type}/sync")
    suspend fun syncNow(@PathVariable type: String): SourceStatus {
        val source = SourceType.entries.find { it.wire == type }?.let(sync::find)
            ?: throw NotFoundException("Source", type)
        return sync.run(source)
    }
}
