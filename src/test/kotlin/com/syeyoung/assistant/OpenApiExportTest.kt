package com.syeyoung.assistant

import org.junit.jupiter.api.Test
import java.io.File

/** Writes the OpenAPI spec to `openapi.json`, the contract that client code is generated from. */
class OpenApiExportTest : ApiTestBase() {
    @Test
    fun `export spec`() {
        val spec = client.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        File("openapi.json").writeText(spec)
    }
}
