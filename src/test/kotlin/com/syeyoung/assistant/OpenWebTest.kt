package com.syeyoung.assistant

import com.sun.net.httpserver.HttpServer
import com.syeyoung.assistant.config.OpenWebConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.InetAddressFilter
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.net.InetSocketAddress

class OpenWebTest {
    private fun server(host: String, respond: (com.sun.net.httpserver.HttpExchange) -> Unit) =
        HttpServer.create(InetSocketAddress(host, 0), 0).apply { createContext("/") { respond(it); it.close() }; start() }

    private fun page(text: String): (com.sun.net.httpserver.HttpExchange) -> Unit = { it.sendResponseHeaders(200, 0); it.responseBody.write(text.toByteArray()) }

    private fun client(allowed: InetAddressFilter) = OpenWebConfig.client(WebClient.builder(), ClientHttpConnectorBuilder.detect(), HttpClientSettings.defaults(), allowed)

    private fun fetch(client: WebClient, url: String): String = runBlocking { client.get().uri(url).retrieve().awaitBody<String>() }

    @Test
    fun `the open web never reaches this machine, by address or by name`() {
        val local = server("127.0.0.1", page("secret"))
        try {
            val url = "http://127.0.0.1:${local.address.port}/"
            // (It answers, so a failure below is the filter's.)
            assertEquals("secret", fetch(WebClient.create(), url))
            val open = client(InetAddressFilter.externalAddresses())
            assertThrows<Exception> { fetch(open, url) }
            assertThrows<Exception> { fetch(open, "http://localhost:${local.address.port}/") }
        } finally {
            local.stop(0)
        }
    }

    @Test
    fun `a redirect is checked like the first request`() {
        val target = server("::1", page("secret"))
        val start = server("127.0.0.1") { it.responseHeaders.add("Location", "http://[::1]:${target.address.port}/"); it.sendResponseHeaders(302, -1) }
        try {
            val url = "http://127.0.0.1:${start.address.port}/"
            assertEquals("secret", fetch(client(InetAddressFilter.all()), url))
            // Allowed to the first server only: the hop to the other is refused.
            assertThrows<Exception> { fetch(client(InetAddressFilter.of("127.0.0.1/32")), url) }
        } finally {
            start.stop(0)
            target.stop(0)
        }
    }
}
