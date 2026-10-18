package com.syeyoung.assistant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("app")
data class AppProperties(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    val auth: Auth = Auth(),
    val llm: Llm = Llm(),
) {
    data class Auth(
        val enabled: Boolean = true,
        val ownerEmail: String = "",
        val googleClientIds: List<String> = emptyList(),
    )

    data class Llm(
        val baseUrl: String = "https://api.openai.com",
        val apiKey: String = "",
        val model: String = "gpt-6-luna",
        val timeout: Duration = Duration.ofSeconds(5),
    )
}
