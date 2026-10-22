package com.syeyoung.assistant

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.drive.DriveExport
import com.syeyoung.assistant.drive.ExportCover
import com.syeyoung.assistant.drive.ExportItem
import com.syeyoung.assistant.drive.ExportRequest
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.sync.Blobs
import com.syeyoung.assistant.sync.SyncDocs
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.imageio.ImageIO

/**
 * [DriveExport] with a real drive (a scratch folder, deleted afterwards): a cover, a note (Korean,
 * math, a diagram, an image beside it) and a notebook's pages, as one PDF. Runs only with
 * DRIVE_TEST_URL, DRIVE_TEST_USER and DRIVE_TEST_PASSWORD_FILE set, like [WebDavLiveTest]; with
 * EXPORT_TEST_OUT, its pages are left there as PNGs to look at.
 */
@EnabledIfEnvironmentVariable(named = "DRIVE_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveExportLiveTest {
    private val props = AppProperties(drive = AppProperties.Drive(
        url = System.getenv("DRIVE_TEST_URL"), username = System.getenv("DRIVE_TEST_USER"),
        password = File(System.getenv("DRIVE_TEST_PASSWORD_FILE") ?: "").takeIf { it.isFile }?.readText()?.trim() ?: "",
    ))
    private val dav = WebDav(props, WebClient.builder())
    private val db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@${TestDatabase.host}:${TestDatabase.port}/postgres"))
    private val scratch = "_api-test-${UUID.randomUUID()}"
    private val docs = SyncDocs(dav, props, db, Clock.systemUTC(), Blobs(db, Clock.systemUTC()))
    private val export = DriveExport(docs)

    init {
        runBlocking {
            for (m in listOf("V16__sync.sql", "V17__sync_blobs.sql")) db.sql(File("src/main/resources/db/migration/$m").readText().replace("create table", "create table if not exists")).await()
            dav.makeFolders("$scratch/img")
        }
    }

    @AfterAll
    fun cleanUp() = runBlocking { dav.delete("$scratch/") }

    private suspend fun write(path: String, bytes: ByteArray, type: String) {
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), bytes.size.toLong(), type)
    }

    @Test
    fun `a cover, a note and a notebook's pages, as one PDF`() = runBlocking<Unit> {
        val png = ByteArrayOutputStream().also { out ->
            val img = BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = java.awt.Color(30, 90, 216); fillRect(0, 0, 120, 60) } }
            ImageIO.write(img, "png", out)
        }.toByteArray()
        write("$scratch/img/plot 1.png", png, "image/png")
        write("$scratch/hw3.md", """
            # 확률및통계 HW3

            **풀이.** 베이즈 정리에 의해 ${'$'}P(A\mid B) = \frac{P(B\mid A)P(A)}{P(B)}${'$'} 이다.

            ${'$'}${'$'}\sum_{i=1}^{n} x_i^2${'$'}${'$'}

            ```mermaid
            graph LR; A-->B
            ```

            ![plot](img/plot%201.png)

            | x | P(x) |
            |---|------|
            | 0 | 0.5  |
        """.trimIndent().toByteArray(), "text/markdown")
        val notebook = PDDocument().use { d -> repeat(3) { d.addPage(PDPage()) }; ByteArrayOutputStream().also { d.save(it) }.toByteArray() }
        write("$scratch/work.pdf", notebook, "application/pdf")

        val pdf = export.export(ExportRequest(
            listOf(ExportItem("$scratch/hw3.md"), ExportItem("$scratch/work.pdf", listOf(0, 2))),
            ExportCover("HW3", "확률및통계", "송시영 20250000", "2026-10-22"),
        ))
        Loader.loadPDF(pdf).use { doc ->
            val text = (1..doc.numberOfPages).map { PDFTextStripper().apply { startPage = it; endPage = it }.getText(doc) }
            assertTrue(text[0].contains("HW3") && text[0].contains("확률및통계"), text[0])
            assertTrue(text[1].contains("베이즈 정리에 의해"), text[1])
            // The note's pages, then the notebook's two.
            assertEquals(1 + (doc.numberOfPages - 3) + 2, doc.numberOfPages)
            System.getenv("EXPORT_TEST_OUT")?.let { dir ->
                File(dir).mkdirs()
                for (i in 0 until doc.numberOfPages) ImageIO.write(PDFRenderer(doc).renderImageWithDPI(i, 60f), "png", File(dir, "page-${i + 1}.png"))
            }
        }
    }
}
