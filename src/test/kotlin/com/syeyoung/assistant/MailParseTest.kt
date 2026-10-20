package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

/** Mail files (.eml) read on the server, as the Mac's Capture sends them. */
class MailParseTest : ApiTestBase() {
    private fun parse(eml: String): Map<*, *> =
        client.post().uri("/api/v1/mail/parse").contentType(MediaType.parseMediaType("message/rfc822")).bodyValue(eml.replace("\n", "\r\n").toByteArray(Charsets.US_ASCII))
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody!!

    @Test
    fun `Korean mail in EUC-KR quoted-printable, and Outlook's ks_c_5601-1987`() {
        val euc = parse("""From: =?UTF-8?B?6rmA6rWQ7IiY?= <prof@postech.ac.kr>
Subject: =?UTF-8?B?7KSR6rCE6rOg7IKsIOyViOuCtA==?=
Message-ID: <abc@postech.ac.kr>
MIME-Version: 1.0
Content-Type: text/plain; charset=euc-kr
Content-Transfer-Encoding: quoted-printable

=BE=C8=B3=E7=C7=CF=BC=BC=BF=E4 =B1=B3=BC=F6=B4=D4
""")
        assertThat(euc["subject"]).isEqualTo("중간고사 안내")
        assertThat(euc["from"]).isEqualTo("김교수 <prof@postech.ac.kr>")
        assertThat(euc["messageId"]).isEqualTo("abc@postech.ac.kr")
        assertThat(euc["text"]).isEqualTo("안녕하세요 교수님")

        val outlook = parse("""From: ta@postech.ac.kr
Subject: hw
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="b1"

--b1
Content-Type: text/plain; charset="ks_c_5601-1987"
Content-Transfer-Encoding: base64

sPrBpiDBpsPiIL7Is7s=
--b1
Content-Type: text/html; charset="ks_c_5601-1987"

<p>html</p>
--b1--
""")
        assertThat(outlook["text"]).isEqualTo("과제 제출 안내")
    }

    @Test
    fun `malformed quoted-printable is read, not fatal`() {
        val m = parse("""From: a@b.c
Subject: x
MIME-Version: 1.0
Content-Type: text/plain; charset=utf-8
Content-Transfer-Encoding: quoted-printable

100% done =ZZ and =4
""")
        assertThat(m["text"] as String).contains("100% done")
    }
}
