package com.syeyoung.assistant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("app")
data class AppProperties(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    val llm: Llm = Llm(),
    val sources: Sources = Sources(),
    val series: SeriesSettings = SeriesSettings(),
) {
    data class SeriesSettings(
        /** Extends repeating events as days pass (at startup and every [extendEvery]). */
        val schedulerEnabled: Boolean = true,
        val extendEvery: Duration = Duration.ofHours(6),
    )

    data class Sources(
        val schedulerEnabled: Boolean = true,
        val initialDelay: Duration = Duration.ofSeconds(30),
        val plms: Plms = Plms(),
        val ical: Ical = Ical(),
    ) {
        data class Ical(
            val subscriptions: List<Subscription> = emptyList(),
            val interval: Duration = Duration.ofHours(12),
            val lookback: java.time.Period = java.time.Period.ofYears(1),
            val lookahead: java.time.Period = java.time.Period.ofYears(2),
        ) {
            /** [key] is stable and part of each item's reference; [tag] is the tag its events get. */
            data class Subscription(val key: String, val name: String, val url: String, val tag: String)
        }

        data class Plms(
            val enabled: Boolean = false,
            val baseUrl: String = "https://plms.postech.ac.kr",
            val username: String = "",
            val password: String = "",
            val interval: Duration = Duration.ofMinutes(30),
            /** How far back overdue deadlines are still fetched. */
            val lookback: Duration = Duration.ofDays(14),
            /** How far ahead deadlines are fetched: the whole semester. */
            val lookahead: Duration = Duration.ofDays(200),
        )
    }

    data class Llm(
        val baseUrl: String = "https://api.openai.com",
        val apiKey: String = "",
        val model: String = "gpt-6-luna",
        val timeout: Duration = Duration.ofSeconds(5),
    )
}
