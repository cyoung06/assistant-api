package com.syeyoung.assistant

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.fromApplication
import java.io.File

/**
 * Local development server: `./gradlew bootTestRun`.
 * Starts an embedded Postgres (data kept in `.devdb/`) and the API on :8080 with auth turned off.
 */
fun main(args: Array<String>) {
    val dataDir = File(".devdb").absoluteFile.apply { mkdirs() }
    val postgres = EmbeddedPostgres.builder()
        .setDataDirectory(dataDir)
        .setCleanDataDirectory(false)
        .setPort(DEV_DB_PORT)
        .start()
    Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })

    System.setProperty("spring.r2dbc.url", "r2dbc:postgresql://localhost:$DEV_DB_PORT/postgres")
    System.setProperty("spring.r2dbc.username", "postgres")
    System.setProperty("spring.r2dbc.password", "postgres")
    System.setProperty("spring.flyway.url", "jdbc:postgresql://localhost:$DEV_DB_PORT/postgres")
    System.setProperty("spring.flyway.user", "postgres")
    System.setProperty("spring.flyway.password", "postgres")
    System.setProperty("app.auth.enabled", "false")

    fromApplication<AssistantApplication>().run(*args)
}

private const val DEV_DB_PORT = 54329
