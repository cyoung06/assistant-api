package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.core.NotebookEdit
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.drive.DriveAnnotations
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.sync.Blobs
import com.syeyoung.assistant.sync.NotebookPdf
import com.syeyoung.assistant.sync.SyncDocs
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * [DriveAnnotations] on a real drive (a scratch folder, deleted afterwards) and the test database:
 * a course file annotated, written on, updated, and the writing carried over. Runs only with
 * DRIVE_TEST_URL, DRIVE_TEST_USER and DRIVE_TEST_PASSWORD_FILE set, like [WebDavLiveTest].
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveAnnotationsLiveTest {
    private val props = AppProperties(drive = AppProperties.Drive(
        url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
        password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
    ))
    private val dav = WebDav(props, WebClient.builder())
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres"))
    private val scratch = "_api-test-${UUID.randomUUID()}"
    private val blobs = Blobs(db, Clock.systemUTC())
    private val docs = SyncDocs(dav, props, db, Clock.systemUTC(), blobs)
    private val annotations = DriveAnnotations(dav, db, Clock.systemUTC(), docs, blobs)

    init {
        runBlocking {
            for (m in listOf("V16__sync.sql", "V17__sync_blobs.sql", "V21__drive_annotations.sql")) {
                db.sql(File("src/main/resources/db/migration/$m").readText().replace("create table", "create table if not exists").replace("create index", "create index if not exists")).await()
            }
            dav.makeFolders("$scratch/A")
        }
    }

    @AfterAll
    fun cleanUp() = runBlocking { dav.delete("$scratch/") }

    private fun pdf(vararg pages: String): ByteArray = PDDocument().use { doc ->
        for (text in pages) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { c ->
                c.beginText()
                c.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                c.newLineAtOffset(72f, 700f)
                c.showText(text)
                c.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private suspend fun write(path: String, bytes: ByteArray) {
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), bytes.size.toLong(), "application/pdf")
    }

    private suspend fun read(path: String): ByteArray {
        val joined = DataBufferUtils.join(dav.get(path)!!.body!!).awaitSingle()
        return ByteArray(joined.readableByteCount()).also { joined.read(it); DataBufferUtils.release(joined) }
    }

    @Test
    fun `writing follows its page to the course file's new version`() = runBlocking {
        val source = "$scratch/A/slides.pdf"
        write(source, pdf("Intro to probability and sample spaces", "Bayes theorem with an example", "A slide taken out later"))
        val a = annotations.annotate(source)
        assertEquals("$scratch/A/Notes/slides (notes).pdf", a.copy)
        // Annotating again opens the same copy.
        assertEquals(a.copy, annotations.annotate(source).copy)

        // Written on the Bayes slide.
        var stroke = ""
        docs.editNotebook(a.copy) { doc ->
            val page = doc.pages().first { it.base == 1 }
            stroke = "stroke-${UUID.randomUUID()}"
            listOf(NotebookEdit.PutStroke(NotebookStroke(stroke, page.id, "pen", 0xff1b1b1fu, 1.5f, listOf(100f, 100f, 0.5f, 200f, 120f, 0.5f), listOf(), null, null)))
        }
        assertFalse(annotations.of(a.copy)!!.sourceChanged)

        // PLMS's new version: a slide put in before it, one taken out.
        write(source, pdf("Intro to probability and sample spaces", "Variance and expectation new this year", "Bayes theorem with an example"))
        assertTrue(annotations.of(a.copy)!!.sourceChanged)
        assertTrue(annotations.carry(a.copy))
        assertFalse(annotations.of(a.copy)!!.sourceChanged)

        // The copy as written: the new version's pages, then the one taken out; the writing on Bayes.
        val file = read(a.copy)
        val notebook = NotebookPdf.read(file)
        assertEquals(4, notebook.pages.size)
        val on = notebook.strokes.single { it.id == stroke }.page
        val index = notebook.pages.indexOfFirst { it.id == on }
        Loader.loadPDF(file).use { doc ->
            fun text(i: Int) = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(doc).trim()
            assertEquals(listOf("Intro to probability and sample spaces", "Variance and expectation new this year", "Bayes theorem with an example", "A slide taken out later"), (0 until 4).map(::text))
            assertEquals("Bayes theorem with an example", text(index))
        }
    }
}
