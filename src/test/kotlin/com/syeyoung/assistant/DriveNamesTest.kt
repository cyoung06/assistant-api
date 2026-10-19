package com.syeyoung.assistant

import com.syeyoung.assistant.drive.DriveService
import com.syeyoung.assistant.source.plms.PlmsFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class DriveNamesTest {
    @Test
    fun `folder names are safe on any system`() {
        assertEquals("컴퓨터시스템 개론 (CSED211-01)", DriveService.safeName("컴퓨터시스템 개론 (CSED211-01)"))
        assertEquals("A B C", DriveService.safeName("A/B:C."))
        assertEquals("tabs and newlines", DriveService.safeName("tabs\tand\nnewlines"))
    }

    @Test
    fun `an updated course file is saved beside the old one`() {
        val at = Instant.parse("2026-10-21T03:00:00Z")
        assertEquals("Lecture 3 (updated 2026-10-21).pdf", PlmsFiles.updatedName("Lecture 3.pdf", at))
        assertEquals("README (updated 2026-10-21)", PlmsFiles.updatedName("README", at))
    }
}
