package com.syeyoung.assistant.sync

import com.syeyoung.assistant.core.NotebookDoc
import com.syeyoung.assistant.core.NotebookEdit
import com.syeyoung.assistant.core.NotebookImage
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.core.SyncDoc

/**
 * An open file's live document (see [SyncDocs]): a note's text, or a notebook's pages and ink. How
 * it's synced is the same for both; how it's made from its file, written to it, and how a change
 * made to the file elsewhere is merged in, is each kind's own.
 */
internal sealed interface Live : AutoCloseable {
    /** What a message did: [reply] to its sender, [broadcast] to the others, and whether the document [changed]. */
    class Got(val reply: ByteArray?, val broadcast: ByteArray?, val changed: Boolean)

    fun start(): ByteArray
    fun receive(message: ByteArray): Got
    fun state(): ByteArray

    /**
     * The file changed elsewhere to [file]: that change, made to the document as [base] (its state
     * when the file last matched) was, merged in; [tell] gets the message for the devices on it.
     * Returns the document as [base] plus that change (it matches the file): the next base.
     */
    suspend fun merge(base: ByteArray, file: ByteArray, tell: (ByteArray) -> Unit): ByteArray

    /** The file's bytes as the document has it now. */
    suspend fun content(): ByteArray

    /** Whether the document matches [file] (written as merged when it doesn't). */
    fun matches(file: ByteArray): Boolean

    /** Lets the document go: it's the core's, in native memory, which the JVM's collector doesn't see filling. */
    override fun close()

    /** One kind of file: how a document is made for it, and its content type. */
    interface Kind {
        val type: String
        /** How long after the last edit it's written. */
        val settle: Long
        /** The first document for [file] (made only here, once: two would hold it twice once merged). */
        suspend fun create(file: ByteArray): Live
        fun load(state: ByteArray): Live
    }
}

/** A note: Markdown text. */
internal class LiveNote(val doc: SyncDoc) : Live {
    override fun start() = doc.start()
    override fun receive(message: ByteArray) = doc.receive(message).let { Live.Got(it.reply, it.broadcast, it.changes.isNotEmpty()) }
    override fun state() = doc.state()
    override fun matches(file: ByteArray) = doc.text() == file.decodeToString()
    override suspend fun content() = doc.text().toByteArray()
    override fun close() = doc.close()

    override suspend fun merge(base: ByteArray, file: ByteArray, tell: (ByteArray) -> Unit): ByteArray {
        SyncDoc.fromState(base).use { fork ->
            val before = fork.text()
            val text = file.decodeToString()
            if (before != text) {
                val (from, to, insert) = SyncDocs.difference(before, text)
                doc.receive(fork.edit(from.toUInt(), to.toUInt(), insert)).broadcast?.let(tell)
            }
            return fork.state()
        }
    }

    object Kind : Live.Kind {
        override val type = "text/markdown; charset=utf-8"
        override val settle = 1000L
        override suspend fun create(file: ByteArray) = LiveNote(SyncDoc.fromText(file.decodeToString()))
        override fun load(state: ByteArray) = LiveNote(SyncDoc.fromState(state))
    }
}

/** A notebook: a PDF with pages, ink and pictures (see [NotebookPdf]); its base PDF and pictures kept in [blobs]. */
internal class LiveNotebook(val doc: NotebookDoc, private val blobs: Blobs) : Live {
    override fun start() = doc.start()
    override fun receive(message: ByteArray) = doc.receive(message).let { Live.Got(it.reply, it.broadcast, it.changes.isNotEmpty()) }
    override fun state() = doc.state()

    /** Written from here (with every save): whether it's the same is told by the merge, not by its bytes. */
    override fun matches(file: ByteArray) = true
    override fun close() = doc.close()

    override suspend fun content(): ByteArray {
        val base = blobs.get(doc.base())?.data ?: error("its base PDF isn't kept")
        val pictures = doc.images().mapNotNull { img -> blobs.get(img.blob)?.let { NotebookPdf.Picture(img, it.data) } }
        return NotebookPdf.write(base, doc.pages(), doc.strokes(), pictures, doc.bookmarks())
    }

    override suspend fun merge(base: ByteArray, file: ByteArray, tell: (ByteArray) -> Unit): ByteArray {
        NotebookDoc.fromState(base).use { fork ->
            val edits = differences(fork, read(file, blobs))
            if (edits.isNotEmpty()) doc.receive(fork.edit(edits)).broadcast?.let(tell)
            return fork.state()
        }
    }

    object Kinds {
        fun of(blobs: Blobs) = object : Live.Kind {
            override val type = "application/pdf"
            // A PDF takes longer to write: once drawing has stopped for a while.
            override val settle = 3000L
            override suspend fun create(file: ByteArray): Live {
                val doc = NotebookDoc()
                doc.edit(differences(doc, read(file, blobs)))
                return LiveNotebook(doc, blobs)
            }
            override fun load(state: ByteArray) = LiveNotebook(NotebookDoc.fromState(state), blobs)
        }
    }

    companion object {
        /** What's in [pdf], its base PDF and pictures kept. */
        private suspend fun read(pdf: ByteArray, blobs: Blobs): Contents {
            val read = NotebookPdf.read(pdf)
            val base = blobs.put(read.base, "application/pdf")
            read.pictures.forEach { blobs.put(it.bytes, it.image.mime) }
            return Contents(base, read.pages, read.strokes, read.pictures.map { it.image }, read.bookmarks)
        }

        private class Contents(val base: String, val pages: List<NotebookPage>, val strokes: List<NotebookStroke>, val images: List<NotebookImage>, val bookmarks: List<String>)

        /**
         * The edits that make [doc] hold [now], by id: what's new or changed put, what's gone removed.
         * A stroke read from a PDF has its points without pressure: only what the file says about it
         * (its page, pen, colour, size, move, Ink data) tells whether it changed.
         */
        private fun differences(doc: NotebookDoc, now: Contents): List<NotebookEdit> {
            val edits = mutableListOf<NotebookEdit>()
            if (doc.base() != now.base) edits += NotebookEdit.SetBase(now.base)
            val pages = doc.pages().associateBy { it.id }
            val wanted = now.pages.map { it.id }.toSet()
            pages.keys.filter { it !in wanted }.forEach { edits += NotebookEdit.RemovePage(it) }
            now.pages.forEachIndexed { i, p ->
                val had = pages[p.id]
                when {
                    had == null -> edits += NotebookEdit.InsertPage(i.toUInt(), p)
                    had != p -> edits += NotebookEdit.PutPage(p)
                }
            }
            val strokes = doc.strokes().associateBy { it.id }
            val strokesNow = now.strokes.associateBy { it.id }
            strokes.keys.filter { it !in strokesNow }.forEach { edits += NotebookEdit.RemoveStroke(it) }
            for (s in now.strokes) {
                val had = strokes[s.id]
                if (had == null || had.page != s.page || had.tool != s.tool || had.color != s.color || had.width != s.width || had.transform != s.transform || !(had.ink contentEquals s.ink)) {
                    // Changed: what the file says, with the points as they were here (they had pressure).
                    edits += NotebookEdit.PutStroke(if (had != null && had.transform == s.transform) s.copy(points = had.points) else s)
                }
            }
            val images = doc.images().associateBy { it.id }
            val imagesNow = now.images.associateBy { it.id }
            images.keys.filter { it !in imagesNow }.forEach { edits += NotebookEdit.RemoveImage(it) }
            now.images.filter { images[it.id] != it }.forEach { edits += NotebookEdit.PutImage(it) }
            val marks = doc.bookmarks().toSet()
            (marks - now.bookmarks.toSet()).forEach { edits += NotebookEdit.Bookmark(it, false) }
            (now.bookmarks.toSet() - marks).forEach { edits += NotebookEdit.Bookmark(it, true) }
            return edits
        }
    }
}
