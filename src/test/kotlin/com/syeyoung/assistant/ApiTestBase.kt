package com.syeyoung.assistant

import com.syeyoung.assistant.dav.CalDav
import com.syeyoung.assistant.dav.Workspace
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The test database: `TEST_DB_HOST`/`TEST_DB_PORT` when set (for environments that run as root,
 * where Postgres refuses to start, so a separate Postgres is needed), otherwise one embedded
 * Postgres process for the whole run. Either way user, password and database are `postgres`.
 */
object TestDatabase {
    private val embedded by lazy { EmbeddedPostgres.builder().start() }

    val host: String = System.getenv("TEST_DB_HOST") ?: "localhost"
    val port: Int by lazy { System.getenv("TEST_DB_PORT")?.toInt() ?: if (System.getenv("TEST_DB_HOST") != null) 5432 else embedded.port }
}

@TestConfiguration(proxyBeanMethods = false)
class TestInfra {
    /** Sunday 2026-10-18, 10:00 in Seoul. */
    @Bean
    @Primary
    fun testClock(): Clock = Clock.fixed(Instant.parse("2026-10-18T01:00:00Z"), ZoneId.of("Asia/Seoul"))

    @Bean
    fun fakeSource() = FakeSource()

    @Bean
    @Primary
    fun fakeCaptureModel() = FakeCaptureModel()
}

@SpringBootTest(properties = ["app.sources.scheduler-enabled=false"])
@Import(TestInfra::class)
abstract class ApiTestBase {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var db: DatabaseClient
    @Autowired lateinit var dav: CalDav
    @Autowired lateinit var ws: Workspace

    lateinit var client: WebTestClient

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val host = TestDatabase.host
            val port = TestDatabase.port
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$host:$port/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "postgres" }
            registry.add("spring.flyway.url") { "jdbc:postgresql://$host:$port/postgres" }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "postgres" }
            registry.add("app.dav.url") { TestRadicale.url }
            registry.add("app.dav.username") { "tester" }
            registry.add("app.dav.password") { "x" }
            registry.add("app.public-host") { "assistant.public.test" }
            registry.add("app.sources.mail.client-id") { "test-client" }
            registry.add("app.sources.mail.authority") { TestMail.oauthUrl }
            registry.add("app.sources.mail.imap-host") { "127.0.0.1" }
            registry.add("app.sources.mail.imap-port") { TestMail.greenMail.imap.port }
            registry.add("app.sources.mail.imap-ssl") { "false" }
            registry.add("app.llm.issuer") { TestOpenAI.url }
            registry.add("app.llm.base-url") { TestOpenAI.url + "/backend-api/codex" }
            registry.add("app.sources.mail.token-key") { "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=" }
        }
    }

    @BeforeEach
    fun setUp() {
        runBlocking {
            db.sql("truncate sources, source_refs, source_calendars, feed_tokens, tasks, projects, labels, task_labels, task_relations, mail_accounts, chatgpt_accounts, initiatives, project_initiatives, ref_index, attachments, public_tokens").await()
            db.sql("alter sequence task_number restart").await()
            // Every test starts with an empty CalDAV home.
            dav.collections().forEach { dav.deleteCollection(it.href) }
            ws.invalidate()
        }
        client = WebTestClient.bindToApplicationContext(context).build()
    }

    // ---- helpers ------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun post(path: String, body: Any): Map<String, Any?> =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isCreated
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun get(path: String): Map<String, Any?> =
        client.get().uri(path).exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun getList(path: String): List<Map<String, Any?>> =
        client.get().uri(path).exchange().expectStatus().isOk
            .expectBody(List::class.java).returnResult().responseBody as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    fun patch(path: String, body: Any, ifMatch: String? = null): Map<String, Any?> =
        client.patch().uri(path).contentType(MediaType.APPLICATION_JSON)
            .headers { h -> ifMatch?.let { h.ifMatch = listOf(it) } }
            .bodyValue(body)
            .exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    fun delete(path: String) {
        client.delete().uri(path).exchange().expectStatus().isNoContent
    }

    /** Puts a raw iCalendar object into the assistant's calendar, as another CalDAV app would. */
    fun putRaw(name: String, ics: String) = runBlocking {
        val cal = ws.assistantCalendar()
        dav.put(cal.href + name, ics.trimIndent().replace("\n", "\r\n") + "\r\n", null)
        ws.invalidate()
    }
}
