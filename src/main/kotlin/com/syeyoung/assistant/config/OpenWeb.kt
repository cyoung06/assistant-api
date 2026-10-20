package com.syeyoung.assistant.config

import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.http.client.InetAddressFilter
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * For fetching URLs that come from outside (links in mail, notes, PLMS): reaches only the public
 * internet, never this server's own network (the cluster, the tailnet, localhost), redirects
 * included. Every address it would connect to is checked as it connects.
 */
class OpenWeb(val client: WebClient)

@Configuration
class OpenWebConfig {
    @Bean
    fun openWeb(builder: WebClient.Builder, connectors: ClientHttpConnectorBuilder<*>, settings: HttpClientSettings) =
        OpenWeb(client(builder, connectors, settings, InetAddressFilter.externalAddresses()))

    companion object {
        /** A client from the app's [settings] that connects only to addresses [allowed] passes. */
        fun client(builder: WebClient.Builder, connectors: ClientHttpConnectorBuilder<*>, settings: HttpClientSettings, allowed: InetAddressFilter): WebClient =
            builder.clientConnector(
                connectors.build(settings.withInetAddressFilter(allowed).withRedirects(HttpRedirects.FOLLOW).withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(6))),
            ).build()
    }
}
