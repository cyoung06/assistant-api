package com.syeyoung.assistant

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.syeyoung.assistant.core.NotebookImage
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.sync.Blobs
import com.syeyoung.assistant.sync.NotebookPdf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Noteshelf's notebooks (its backups, .nsa) as ours: `./gradlew noteshelfImport -Pfrom=<folder> -Pto=<folder>`.
 * Every .nsa under `from` becomes a notebook PDF at the same place under `to`: the same pages (the
 * PDF it was written on, or its paper), its pen and highlighter strokes as our ink (editable, the
 * width of each bit as written), its pictures, and its bookmarks. A one-time move; `from` is only read.
 *
 * A backup is a ZIP of `<name>.ns_a/`: Document.plist (the pages: each a page of one of the PDFs in
 * Templates/, and its size there, as Noteshelf lays it out, 1752 wide), Annotations/<page> (SQLite:
 * a stroke's segments as 7 floats each, start and end x, y, thickness, opacity, unused; a picture's
 * rectangle, its file in Resources/<id>.png), all in that layout's units from the page's top left.
 */
fun main(args: Array<String>) {
    val (from, to) = args.map(::File)
    val all = from.walk().filter { it.isFile && it.extension == "nsa" }.sortedBy { it.path }.toList()
    var failed = 0
    all.forEachIndexed { i, nsa ->
        val out = File(to, nsa.relativeTo(from).path.removeSuffix(".nsa") + ".pdf")
        runCatching {
            out.parentFile.mkdirs()
            val (pdf, summary) = NoteshelfImport.convert(nsa)
            out.writeBytes(pdf)
            println("[${i + 1}/${all.size}] ${nsa.relativeTo(from)}: $summary")
        }.onFailure { failed++; println("[${i + 1}/${all.size}] ${nsa.relativeTo(from)}: FAILED ${it.message}") }
    }
    println("Done: ${all.size - failed} converted, $failed failed")
}

object NoteshelfImport {
    private const val PEN = 0
    private const val HIGHLIGHTER = 3
    private const val STROKE = 0
    private const val PICTURE = 2
    /** Our highlighter's see-through, as the apps' highlighters have it. */
    private const val HIGHLIGHT_ALPHA = 0x66

    fun convert(nsa: File): Pair<ByteArray, String> = ZipFile(nsa).use { zip ->
        val root = zip.entries().asSequence().first { it.name.endsWith("Document.plist") }.name.substringBeforeLast("Document.plist")
        fun bytes(name: String) = zip.getEntry(root + name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        val doc = PropertyListParser.parse(bytes("Document.plist")) as NSDictionary
        val sources = HashMap<String, PDDocument>()
        try {
            PDDocument().use { base ->
                val pages = mutableListOf<NotebookPage>()
                val strokes = mutableListOf<NotebookStroke>()
                val pictures = mutableListOf<NotebookPdf.Picture>()
                val bookmarks = mutableListOf<String>()
                for (entry in (doc["pages"] as NSArray).array) {
                    val p = entry as NSDictionary
                    val file = p["associatedPDFFileName"].toString()
                    val source = sources.getOrPut(file) { Loader.loadPDF(bytes("Templates/$file") ?: error("$file missing")) }
                    val page = base.importPage(source.getPage(p["associatedPDFKitPageIndex"].toString().toInt() - 1))
                    val box = page.cropBox
                    val turned = ((page.rotation % 360) + 360) % 360 in setOf(90, 270)
                    val (w, h) = if (turned) box.height to box.width else box.width to box.height
                    val id = UUID.randomUUID().toString()
                    pages += NotebookPage(id, pages.size, "", w, h)
                    if (p["isBookmarked"]?.toString() == "true" || p["isBookmarked"]?.toString() == "1") bookmarks += id
                    // Noteshelf's layout of the page ({{x, y}, {w, h}}) to the page's points.
                    val rect = Regex("""-?[\d.]+""").findAll(p["pdfKitPageRect"].toString()).map { it.value.toFloat() }.toList()
                    val scale = w / rect[2]
                    val db = bytes("Annotations/${p["uuid"]}") ?: continue
                    read(db) { type, pen, color, segments, id2, x, y, rw, rh ->
                        when {
                            type == STROKE && segments != null -> stroke(id, pen, color, segments, scale)?.let { strokes += it }
                            type == PICTURE -> {
                                val png = bytes("Resources/$id2.png") ?: return@read
                                pictures += NotebookPdf.Picture(NotebookImage(UUID.randomUUID().toString(), id, listOf(x * scale, y * scale, rw * scale, rh * scale), listOf(0f, 0f, 1f, 1f), Blobs.hashOf(png), "image/png"), png)
                            }
                        }
                    }
                }
                val baseBytes = ByteArrayOutputStream().also { base.save(it) }.toByteArray()
                NotebookPdf.write(baseBytes, pages, strokes, pictures, bookmarks) to "${pages.size} pages, ${strokes.size} strokes, ${pictures.size} pictures"
            }
        } finally {
            sources.values.forEach { it.close() }
        }
    }

    private fun read(db: ByteArray, each: (type: Int, pen: Int, color: Long, segments: ByteArray?, id: String?, x: Float, y: Float, w: Float, h: Float) -> Unit) {
        val file = File.createTempFile("noteshelf", ".sqlite")
        try {
            file.writeBytes(db)
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
                c.createStatement().executeQuery("select annotationType, penType, strokeColor, stroke_segments_v3, id, boundingRect_x, boundingRect_y, boundingRect_w, boundingRect_h from annotation order by createdTime").use { r ->
                    while (r.next()) each(r.getInt(1), r.getInt(2), r.getLong(3), r.getBytes(4), r.getString(5), r.getFloat(6), r.getFloat(7), r.getFloat(8), r.getFloat(9))
                }
            }
        } finally {
            file.delete()
        }
    }

    /** A stroke's segments (each from the last one's end) as our stroke: its points, and its outline at each point's thickness. */
    private fun stroke(page: String, pen: Int, color: Long, segments: ByteArray, scale: Float): NotebookStroke? {
        val f = ByteBuffer.wrap(segments).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = f.limit() / 7
        if (n == 0) return null
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        val thick = FloatArray(n + 1)
        xs[0] = f[0] * scale; ys[0] = f[1] * scale; thick[0] = f[4] * scale
        for (i in 0 until n) {
            xs[i + 1] = f[i * 7 + 2] * scale; ys[i + 1] = f[i * 7 + 3] * scale; thick[i + 1] = f[i * 7 + 4] * scale
        }
        val highlighter = pen == HIGHLIGHTER
        val rgb = (color and 0xFFFFFF).toUInt()
        // Our pen's width at full pressure is 1.6 times the stroke's; each point's pressure gives its thickness.
        val width = if (highlighter) thick.max() else thick.max() / 1.6f
        val points = ArrayList<Float>((n + 1) * 3)
        for (i in 0..n) {
            points += xs[i]; points += ys[i]
            points += if (highlighter) 0.5f else ((thick[i] / width - 0.4f) / 1.2f).coerceIn(0f, 1f)
        }
        return NotebookStroke(
            UUID.randomUUID().toString(), page,
            if (highlighter) "highlighter" else "pen",
            if (highlighter) (HIGHLIGHT_ALPHA.toUInt() shl 24) or rgb else 0xFF000000u or rgb,
            width, points, listOf(outline(xs, ys, thick)), null, null,
        )
    }

    /** The filled shape through the points, [thick] wide at each, round at the ends (as the web app's outlineOf). */
    private fun outline(xs: FloatArray, ys: FloatArray, thick: FloatArray): List<Float> {
        val n = xs.size
        fun half(i: Int) = thick[i] / 2
        if (n == 1 || (xs.all { it == xs[0] } && ys.all { it == ys[0] })) {
            val r = half(0)
            return (0 until 12).flatMap { k -> listOf(xs[0] + r * cos(k * PI / 6).toFloat(), ys[0] + r * sin(k * PI / 6).toFloat()) }
        }
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()
        for (i in 0 until n) {
            val a = maxOf(0, i - 1)
            val b = minOf(n - 1, i + 1)
            var dx = xs[b] - xs[a]
            var dy = ys[b] - ys[a]
            val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            dx /= len; dy /= len
            val h = half(i)
            left += xs[i] - dy * h; left += ys[i] + dx * h
            right += xs[i] + dy * h; right += ys[i] - dx * h
        }
        fun cap(i: Int, from: Double) = (1 until 6).flatMap { k -> listOf(xs[i] + half(i) * cos(from + k * PI / 6).toFloat(), ys[i] + half(i) * sin(from + k * PI / 6).toFloat()) }
        fun dir(i: Int, j: Int) = atan2((ys[j] - ys[i]).toDouble(), (xs[j] - xs[i]).toDouble())
        val reversed = right.chunked(2).reversed().flatten()
        return left + cap(n - 1, dir(n - 2, n - 1) - PI / 2) + reversed + cap(0, dir(1, 0) - PI / 2)
    }
}
