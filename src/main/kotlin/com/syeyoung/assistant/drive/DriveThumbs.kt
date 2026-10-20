package com.syeyoung.assistant.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessStreamCache
import org.apache.pdfbox.io.ScratchFile
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Previews of the drive's PDFs (their first page, as a small JPEG), for icon views. */
@Component
class DriveThumbs(private val dav: WebDav) {
    /** Recent previews by path and version (a few hundred, some KB each). */
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 300
    })
    /** One at a time: a big PDF takes memory to open. */
    private val rendering = Semaphore(1)

    /** The first page of the PDF at [path], [width] pixels wide; null if it isn't there or can't be read. */
    suspend fun pdf(path: String, version: String, width: Int): ByteArray? {
        val key = "$path|$version|$width"
        cache[key]?.let { return it }
        return rendering.withPermit {
            cache[key] ?: run {
                val r = dav.get(path) ?: return@run null
                val file = withContext(Dispatchers.IO) { File.createTempFile("thumb", ".pdf") }
                try {
                    DataBufferUtils.write(r.body ?: Flux.empty(), file.toPath()).awaitSingleOrNull()
                    // (PDFBox reads and renders blocking.)
                    withContext(Dispatchers.IO) { runCatching { render(file, width) }.getOrNull() }
                } finally {
                    withContext(Dispatchers.IO) { file.delete() }
                }
            }?.also { cache[key] = it }
        }
    }

    private fun render(file: File, width: Int): ByteArray =
        Loader.loadPDF(file, null as String?, null, null, RandomAccessStreamCache.StreamCacheCreateFunction { ScratchFile.getMainMemoryOnlyInstance() }).use { doc ->
            val page = doc.getPage(0)
            val scale = width / page.cropBox.width
            val image = PDFRenderer(doc).renderImage(0, scale, ImageType.RGB)
            val out = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.8f })
            }
            writer.dispose()
            out.toByteArray()
        }
}
