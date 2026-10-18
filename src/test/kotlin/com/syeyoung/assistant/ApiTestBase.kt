package com.syeyoung.assistant

import com.syeyoung.assistant.quickadd.ItemExtractor
import com.syeyoung.assistant.quickadd.LlmDraft
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

/** Fake LLM: tests set [next] to the draft (or error) the next call should produce. */
class FakeExtractor : ItemExtractor {
    var next: () -> LlmDraft = { error("no draft configured") }
    var lastInstructions: String? = null
    override val configured = true
    override suspend fun extract(instructions: String, text: String): LlmDraft {
        lastInstructions = instructions
        return next()
    }
}

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
    @Primary
    fun fakeExtractor() = FakeExtractor()
}

@SpringBootTest
@Import(TestInfra::class)
abstract class ApiTestBase {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var db: DatabaseClient
    @Autowired lateinit var extractor: FakeExtractor

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
        }
    }

    @BeforeEach
    fun setUp() {
        runBlocking { db.sql("truncate items, tags").await() }
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
    fun patch(path: String, body: Any, ifMatch: Long? = null): Map<String, Any?> =
        client.patch().uri(path).contentType(MediaType.APPLICATION_JSON)
            .headers { h -> ifMatch?.let { h.ifMatch = listOf("\"$it\"") } }
            .bodyValue(body)
            .exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    fun tag(name: String, color: String) = post("/api/v1/tags", mapOf("name" to name, "color" to color))["id"] as String

    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.derived() = this["derived"] as Map<String, Any?>
}
