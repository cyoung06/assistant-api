package com.syeyoung.assistant.sync

import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.netty.http.server.WebsocketServerSpec
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

/**
 * A note's live editing, over a WebSocket at `/api/v1/sync?doc=<drive path>`: the y-websocket
 * protocol (binary y-protocols messages, one note per connection), as Yjs' own WebsocketProvider and
 * the apps' core speak it. No sign-in, as with the rest of the API (the tailnet is the boundary).
 */
@Component
class SyncSocket(private val docs: SyncDocs) : WebSocketHandler {
    override fun handle(session: WebSocketSession): Mono<Void> {
        val path = UriComponentsBuilder.fromUri(session.handshakeInfo.uri).build().queryParams.getFirst("doc")
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
            ?.split('/')?.filter { it.isNotEmpty() && it != "." }
            ?.takeIf { parts -> parts.none { it == ".." } }?.joinToString("/")
        if (path == null || !docs.syncable(path)) return session.close(CloseStatus.POLICY_VIOLATION.withReason("not a note that can be edited live"))

        // Messages to this device, from any thread (others' edits arrive on theirs): one at a time.
        val out = Sinks.many().unicast().onBackpressureBuffer<ByteArray>()
        val peer = object : SyncDocs.Peer {
            override fun send(message: ByteArray) = synchronized(out) { out.tryEmitNext(message); Unit }
            // The note moved or was deleted: the device is told so, and doesn't come back to it.
            override fun close() = session.close(CloseStatus.POLICY_VIOLATION.withReason("moved or deleted")).subscribe().let { }
        }

        return mono { docs.join(path, peer) }
            .switchIfEmpty(Mono.defer { session.close(CloseStatus.POLICY_VIOLATION.withReason("no such note")).then(Mono.empty()) })
            .flatMap { room ->
                // As y-websocket's server does: its sync step 1 first, so the device sends what it has.
                peer.send(docs.greeting(room))
                val input = session.receive()
                    .map { m -> ByteArray(m.payload.readableByteCount()).also { m.payload.read(it) } }
                    .concatMap { message -> mono { docs.receive(room, peer, message) } }
                    .then()
                    .doFinally { synchronized(out) { out.tryEmitComplete() } }
                val output = session.send(out.asFlux().map { bytes -> session.binaryMessage { it.wrap(bytes) } })
                Mono.`when`(input, output).doFinally { mono { docs.leave(room, peer) }.subscribe() }
            }
    }
}

@Configuration
class SyncSocketConfig : WebFluxConfigurer {
    @Bean
    fun syncSocketMapping(socket: SyncSocket): HandlerMapping = SimpleUrlHandlerMapping(mapOf("/api/v1/sync" to socket), -1)

    /**
     * Messages up to [MAX_MESSAGE] (Reactor Netty's default is 64 KiB a frame): a device catching up
     * after editing offline sends all it did at once, and a notebook's stroke carries its outline.
     */
    override fun getWebSocketService(): WebSocketService =
        HandshakeWebSocketService(ReactorNettyRequestUpgradeStrategy { WebsocketServerSpec.builder().maxFramePayloadLength(MAX_MESSAGE) })

    companion object {
        const val MAX_MESSAGE = 64 shl 20
    }
}
