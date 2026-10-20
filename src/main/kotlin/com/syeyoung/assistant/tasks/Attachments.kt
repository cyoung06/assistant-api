package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import com.syeyoung.assistant.common.servedSafely
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AttachmentInfo(val id: UUID, val filename: String, val contentType: String, val size: Int, val createdAt: Instant)

data class AttachmentData(val info: AttachmentInfo, val bytes: ByteArray)

@Repository
class AttachmentRepository(private val db: DatabaseClient) {

    suspend fun insert(filename: String, contentType: String, bytes: ByteArray, now: Instant): AttachmentInfo {
        val id = UUID.randomUUID()
        db.sql("insert into attachments (id, filename, content_type, size, data, created_at) values (:id, :name, :type, :size, :data, :now)")
            .bind("id", id).bind("name", filename).bind("type", contentType).bind("size", bytes.size).bind("data", ByteBuffer.wrap(bytes)).bind("now", now)
            .fetch().awaitRowsUpdated()
        return AttachmentInfo(id, filename, contentType, bytes.size, now)
    }

    suspend fun get(id: UUID): AttachmentData? =
        db.sql("select * from attachments where id = :id").bind("id", id).map { r ->
            val b = r.req<ByteBuffer>("data")
            AttachmentData(info(r), ByteArray(b.remaining()).also { b.get(it) })
        }.awaitOneOrNull()

    /** Attachments of each task, by task. */
    suspend fun byTask(): Map<UUID, List<AttachmentInfo>> =
        db.sql("select id, task_id, filename, content_type, size, created_at from attachments where task_id is not null order by created_at")
            .map { r -> r.req<UUID>("task_id") to info(r) }.flow().toList().groupBy({ it.first }, { it.second })

    /** Makes [ids] exactly the task's attachments (others it had are deleted). */
    suspend fun setForTask(taskId: UUID, ids: List<UUID>) {
        db.sql("delete from attachments where task_id = :task and not (id = any(:ids))").bind("task", taskId).bind("ids", ids.toTypedArray())
            .fetch().awaitRowsUpdated()
        if (ids.isNotEmpty()) {
            db.sql("update attachments set task_id = :task where id = any(:ids)").bind("task", taskId).bind("ids", ids.toTypedArray())
                .fetch().awaitRowsUpdated()
        }
    }

    suspend fun exists(ids: List<UUID>): Boolean =
        ids.isEmpty() || db.sql("select count(*) as n from attachments where id = any(:ids)").bind("ids", ids.distinct().toTypedArray())
            .map { r -> r.req<Long>("n") }.awaitOneOrNull() == ids.distinct().size.toLong()

    suspend fun delete(id: UUID) {
        db.sql("delete from attachments where id = :id").bind("id", id).fetch().awaitRowsUpdated()
    }

    /** Keeps uploads with a triage input until a suggestion from it is accepted. */
    suspend fun holdForInput(inputId: UUID, ids: List<UUID>) {
        if (ids.isEmpty()) return
        db.sql("update attachments set triage_input_id = :input where id = any(:ids) and task_id is null")
            .bind("input", inputId).bind("ids", ids.toTypedArray()).fetch().awaitRowsUpdated()
    }

    /** The uploads kept with a triage input. */
    suspend fun forInput(inputId: UUID): List<AttachmentInfo> =
        db.sql("select id, task_id, filename, content_type, size, created_at from attachments where triage_input_id = :input order by created_at")
            .bind("input", inputId).map { r -> info(r) }.flow().toList()

    /** Uploads that never got attached to a task. */
    suspend fun deleteOrphans(before: Instant) {
        db.sql("delete from attachments where task_id is null and triage_input_id is null and created_at < :before").bind("before", before).fetch().awaitRowsUpdated()
    }

    private fun info(r: io.r2dbc.spi.Readable) =
        AttachmentInfo(r.req("id"), r.req("filename"), r.req("content_type"), r.req("size"), r.req("created_at"))
}

@RestController
@RequestMapping("/api/v1/attachments")
class AttachmentController(private val repo: AttachmentRepository, private val clock: Clock) {

    /** Uploads a file (multipart field `file`, at most 15 MB); attach it with a task's `attachmentIds`. */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun upload(@RequestPart("file") file: FilePart): AttachmentInfo {
        val buffer = DataBufferUtils.join(file.content(), MAX_BYTES).awaitSingle()
        val bytes = ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
        DataBufferUtils.release(buffer)
        if (bytes.isEmpty()) throw ValidationException("The file is empty")
        val type = file.headers().contentType?.toString() ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
        val now = Instant.now(clock)
        repo.deleteOrphans(now.minus(Duration.ofDays(1)))
        return repo.insert(file.filename().ifBlank { "file" }, type, bytes, now)
    }

    @GetMapping("/{id}")
    suspend fun download(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val a = repo.get(id) ?: throw NotFoundException("Attachment", id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(a.info.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
            .servedSafely(a.info.contentType, a.info.filename)
            .body(a.bytes)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteAttachment(@PathVariable id: UUID) = repo.delete(id)

    private companion object {
        const val MAX_BYTES = 15 * 1024 * 1024
    }
}
