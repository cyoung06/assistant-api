package com.syeyoung.assistant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("app")
data class AppProperties(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    /** Where the web app is reached; links to the app's own things (tasks, events, mail) start with it. */
    val publicUrl: String = "http://localhost:5173",
    /**
     * The internet-facing host (for the Outlook add-in). Requests arriving through it may only use
     * paths under /api/v1/public/; empty means there is none.
     */
    val publicHost: String = "",
    val sources: Sources = Sources(),
    val llm: Llm = Llm(),
    val github: Github = Github(),
    val dav: Dav = Dav(),
) {
    /** The CalDAV server (Radicale) holding events and todos. */
    data class Dav(
        val url: String = "http://localhost:5232",
        val username: String = "",
        val password: String = "",
    )

    data class Sources(
        val schedulerEnabled: Boolean = true,
        val initialDelay: Duration = Duration.ofSeconds(30),
        val plms: Plms = Plms(),
        val ical: Ical = Ical(),
        val mail: Mail = Mail(),
        val contests: Contests = Contests(),
    ) {
        /** Upcoming programming contests as events: AtCoder (by contest ID prefix) and Codeforces (by name). */
        data class Contests(
            val enabled: Boolean = true,
            val interval: Duration = Duration.ofHours(6),
            val atcoder: List<String> = listOf("abc", "arc", "agc"),
            val codeforces: List<String> = listOf("Div. 1", "Div. 2", "Div. 3", "Div. 4", "Educational"),
        )

        /**
         * Microsoft 365 mail (POSTECH's Outlook) over IMAP with OAuth. [clientId] is our app
         * registration in Microsoft Entra (a public client: no secret); empty turns the source off.
         * [tokenKey] encrypts the stored refresh token (base64, 32 bytes).
         */
        data class Mail(
            val clientId: String = "",
            val tenant: String = "organizations",
            val authority: String = "https://login.microsoftonline.com",
            val imapHost: String = "outlook.office365.com",
            val imapPort: Int = 993,
            val imapSsl: Boolean = true,
            val tokenKey: String = "",
            val interval: Duration = Duration.ofMinutes(5),
            /** Every new mail in the watched folders goes to Triage (read-only: nothing is marked read). */
            val triage: Boolean = true,
            val triageInterval: Duration = Duration.ofMinutes(2),
            /** On the very first look, mail this old is read too. */
            val triageBacklog: Duration = Duration.ofDays(3),
        )

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
            /** How often course files and board posts are listed for references. */
            val catalogInterval: Duration = Duration.ofHours(6),
        )
    }

    /**
     * The model that reads shared screenshots and text on request (capture), used through a ChatGPT
     * sign-in (Codex's OAuth client), so it runs on the account's Codex quota rather than an API key.
     */
    /** GitHub, for references to repos, issues and pull requests. */
    data class Github(val apiUrl: String = "https://api.github.com", val webUrl: String = "https://github.com")

    data class Llm(
        val issuer: String = "https://auth.openai.com",
        val clientId: String = "app_EMoamEEZ73f0CkXaXp7hrann",
        val baseUrl: String = "https://chatgpt.com/backend-api/codex",
        val model: String = "gpt-6-luna",
        val timeout: Duration = Duration.ofSeconds(90),
        /** How many Triage inputs are read at once. */
        val triageConcurrency: Int = 10,
        /** At most this many Triage inputs are read a day; the rest wait for the next day. */
        val triageDailyLimit: Int = 200,
        /** How often Triage looks for inputs to read (new ones are read right away). */
        val triageInterval: Duration = Duration.ofSeconds(30),
    )
}
