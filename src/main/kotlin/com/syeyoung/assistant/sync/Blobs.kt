package com.syeyoung.assistant.sync

import com.syeyoung.assistant.common.servedSafely
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Bytes kept by their content's hash ("sha256-<hex>"): a live notebook's base PDF and the pictures
 * on its pages, which the notebook's document refers to rather than holds (every device loads the
 * document; a picture only when it's shown). The same bytes are kept once.
 */
@Component
class Blobs(private val db: DatabaseClient, private val clock: Clock) {
    class Blob(val mime: String, val data: ByteArray)

    /** Keeps [data]; its hash. */
    suspend fun put(data: ByteArray, mime: String): String {
        val hash = hashOf(data)
        db.sql("insert into sync_blobs (hash, mime, data, created_at) values (:hash, :mime, :data, :now) on conflict (hash) do nothing")
            .bind("hash", hash).bind("mime", mime).bind("data", ByteBuffer.wrap(data)).bind("now", Instant.now(clock))
            .fetch().awaitRowsUpdated()
        return hash
    }

    suspend fun get(hash: String): Blob? =
        db.sql("select mime, data from sync_blobs where hash = :hash").bind("hash", hash)
            .map { r -> Blob(r.get("mime", String::class.java)!!, r.get("data", ByteBuffer::class.java)!!.let { b -> ByteArray(b.remaining()).also { b.get(it) } }) }
            .awaitOneOrNull()

    companion object {
        fun hashOf(data: ByteArray) = "sha256-" + MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}

/** A live notebook's pictures and base PDF, by hash (they never change: cached for good). */
@RestController
@RequestMapping("/api/v1/sync/blobs")
class BlobsController(private val blobs: Blobs) {
    @GetMapping("/{hash}")
    suspend fun get(@PathVariable hash: String): ResponseEntity<ByteArray> {
        val b = blobs.get(hash) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(b.mime))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .servedSafely(b.mime)
            .body(b.data)
    }

    /** A picture placed on a notebook's page, kept; its hash (to put in the notebook). */
    @PostMapping
    suspend fun put(@RequestBody data: ByteArray, @RequestHeader("Content-Type") type: String): BlobPut {
        // Pictures that can't carry script (not SVG), as the pages show them.
        val mime = type.substringBefore(';').trim().lowercase()
        if (mime !in PICTURES) throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PNG, JPEG, GIF or WebP pictures only")
        return BlobPut(blobs.put(data, mime))
    }

    companion object {
        private val PICTURES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}

data class BlobPut(val hash: String)
