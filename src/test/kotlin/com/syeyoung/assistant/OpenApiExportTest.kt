package com.syeyoung.assistant

import org.junit.jupiter.api.Test
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Writes the OpenAPI spec to `openapi.json`, the contract that client code is generated from.
 * Keys are sorted because springdoc's ordering varies between runs; CI checks the file is current.
 */
class OpenApiExportTest : ApiTestBase() {
    @Test
    fun `export spec`() {
        val spec = client.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
        val tree = mapper.readValue(spec, Map::class.java)
        File("openapi.json").writeText(mapper.writeValueAsString(tree) + "\n")
    }
}
