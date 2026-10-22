package com.syeyoung.assistant

import com.syeyoung.assistant.source.plms.PlmsCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A PLMS board post's text, as it goes to Triage. */
class PlmsPostTextTest {
    @Test
    fun `the main region's text, lines kept, without the page around it`() {
        val html = """
            <html><body><nav>Dashboard · My courses</nav>
            <div role="main"><h2>[공지] 중간고사 안내</h2>
            <div class="content"><p>중간고사는 10월 27일 화요일&nbsp;12:30에 봅니다.</p><p>범위: 1&amp;2장<br>계산기 지참</p>
            <script>var x = "<p>not this</p>";</script></div></div>
            <footer>© POSTECH</footer></body></html>
        """.trimIndent()
        assertEquals("[공지] 중간고사 안내\n중간고사는 10월 27일 화요일 12:30에 봅니다.\n범위: 1&2장\n계산기 지참", PlmsCatalog.postText(html))
    }
}
