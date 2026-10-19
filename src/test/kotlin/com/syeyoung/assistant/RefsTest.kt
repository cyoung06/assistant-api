package com.syeyoung.assistant

import com.syeyoung.assistant.mail.MailAccounts
import com.syeyoung.assistant.refs.LinkMigration
import com.syeyoung.assistant.refs.Ref
import com.syeyoung.assistant.refs.RefIndex
import com.syeyoung.assistant.refs.RefKind
import com.syeyoung.assistant.source.plms.PlmsCatalog
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class RefsTest : ApiTestBase() {
    @Autowired lateinit var migration: LinkMigration
    @Autowired lateinit var index: RefIndex
    @Autowired lateinit var catalog: PlmsCatalog
    @Autowired lateinit var accounts: MailAccounts

    private val base = "http://localhost:5173"

    @Suppress("UNCHECKED_CAST")
    private fun withParam(path: String, name: String, value: String): Any? =
        client.get().uri { it.path(path).queryParam(name, "{v}").build(value) }.exchange().expectStatus().isOk
            .expectBody(Any::class.java).returnResult().responseBody

    @Suppress("UNCHECKED_CAST")
    private fun search(q: String) = withParam("/api/v1/refs", "q", q) as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun resolve(url: String) = withParam("/api/v1/refs/resolve", "url", url) as Map<String, Any?>

    private fun indexPlms() = runBlocking {
        index.replace(
            "plms",
            listOf(
                Ref(RefKind.PLMS_FILE, "HW6 problems", "plms:mod/ubfile/101", "확률및통계"),
                Ref(RefKind.PLMS_NOTICE, "HW6 deadline extended", "plms:post/55/9", "확률및통계 · 공지사항", Instant.parse("2026-10-17T03:00:00Z")),
            ),
            Instant.now(),
        )
    }

    @Test
    fun `search finds tasks, projects, events and PLMS things`() {
        val p = post("/api/v1/projects", mapOf("name" to "HW club"))
        val t = post("/api/v1/tasks", mapOf("title" to "HW6", "projectId" to p["id"]))
        post("/api/v1/events", mapOf("title" to "HW6 review session", "start" to "2026-10-21T15:00:00+09:00", "end" to "2026-10-21T16:00:00+09:00"))
        indexPlms()

        val found = search("hw6")
        assertThat(found.map { it["kind"] to it["title"] }).contains(
            "task" to "#1 HW6", "event" to "HW6 review session", "plms_file" to "HW6 problems", "plms_notice" to "HW6 deadline extended",
        )
        assertThat(found.first { it["kind"] == "task" }["url"]).isEqualTo("$base/task/${t["id"]}")
        assertThat(search("#1").first()["title"]).isEqualTo("#1 HW6")
        assertThat(search("club").map { it["kind"] }).contains("project")
    }

    @Test
    fun `pasted links are named - ours, indexed PLMS pages, and other sites`() {
        val t = post("/api/v1/tasks", mapOf("title" to "Read [the slides](https://example.test/x)"))
        assertThat(resolve("https://assistant.example/task/${t["id"]}")).containsEntry("title", "#1 Read the slides").containsEntry("kind", "task")
        // The old #fragment form still resolves.
        assertThat(resolve("https://assistant.example/#task/${t["id"]}")).containsEntry("url", "$base/task/${t["id"]}")
        indexPlms()
        // Board links carry paging parameters; they resolve to the indexed post.
        assertThat(resolve("https://plms.postech.ac.kr/mod/ubboard/article.php?keyfield&keyword&ls=15&page=1&bwid=9&id=55"))
            .containsEntry("title", "HW6 deadline extended").containsEntry("kind", "plms_notice").containsEntry("url", "plms:post/55/9")
        assertThat(resolve("plms:mod/ubfile/101")).containsEntry("title", "HW6 problems")
        // Local addresses are never fetched; the link is named after its host and path.
        assertThat(resolve("http://127.0.0.1:1/page")).containsEntry("kind", "link").containsEntry("title", "127.0.0.1/page")
        client.get().uri { it.path("/api/v1/refs/resolve").queryParam("url", "{v}").build("javascript:alert(1)") }.exchange().expectStatus().isBadRequest
    }

    @Test
    fun `backlinks list what links to a task`() {
        val target = post("/api/v1/tasks", mapOf("title" to "Midterm"))
        val link = "$base/task/${target["id"]}"
        post("/api/v1/tasks", mapOf("title" to "Study plan", "description" to "Before [#1 Midterm]($link)"))
        post("/api/v1/events", mapOf("title" to "Study group for [#1 Midterm]($link)", "start" to "2026-10-21T15:00:00+09:00", "end" to "2026-10-21T16:00:00+09:00"))
        @Suppress("UNCHECKED_CAST")
        val back = withParam("/api/v1/refs/backlinks", "url", link) as List<Map<String, Any?>>
        assertThat(back.map { it["kind"] to it["title"] }).containsExactlyInAnyOrder("task" to "#2 Study plan", "event" to "Study group for #1 Midterm")
    }

    @Test
    fun `linked titles stay linked here and plain in iCalendar`() {
        val e = post("/api/v1/events", mapOf("title" to "Review [HW6](https://plms.postech.ac.kr/mod/ubfile/view.php?id=101)", "start" to "2026-10-21T15:00:00+09:00", "end" to "2026-10-21T16:00:00+09:00"))
        val raw = runBlocking { dav.get(ws.findEvent(e["id"] as String)!!.href)!!.data }
        assertThat(raw.replace("\r\n ", "")).contains("SUMMARY:Review HW6", "X-ASSISTANT-TITLE:Review [HW6](https://plms.postech.ac.kr/mod/ubfile/view.php?id=101)")
        assertThat(get("/api/v1/events/${e["id"]}")["title"]).isEqualTo("Review [HW6](https://plms.postech.ac.kr/mod/ubfile/view.php?id=101)")

        post("/api/v1/tasks", mapOf("title" to "Submit [HW6](https://plms.postech.ac.kr/mod/assign/view.php?id=7)", "due" to mapOf("date" to "2026-10-20")))
        val feed = client.get().uri(get("/api/v1/ical/feed")["path"] as String).exchange().expectBody(String::class.java).returnResult().responseBody!!
        assertThat(feed).contains("SUMMARY:Submit HW6", "SUMMARY:Due: Submit HW6").doesNotContain("SUMMARY:Submit [HW6]")
    }

    @Test
    fun `PLMS course and board pages become references`() {
        val course = """
            <li class="activity ubboard modtype_ubboard " id="module-232395"><div><a class="aalink " href="https://plms.postech.ac.kr/mod/ubboard/view.php?id=232395"><span class="instancename">공지사항<span class="accesshide " > 게시판</span></span></a></div></li>
            <li class="activity label modtype_label " id="module-5"><div>Week 1 text only</div></li>
            <li class="activity ubfile modtype_ubfile " id="module-239957"><div><a href="https://plms.postech.ac.kr/mod/ubfile/view.php?id=239957"><span class="instancename">7. ChordalGraphs &amp; more<span class="accesshide " > 파일</span></span></a></div></li>
            <li class="activity assign modtype_assign " id="module-300"><div><a href="https://plms.postech.ac.kr/mod/assign/view.php?id=300"><span class="instancename">HW6</span></a></div></li>
        """
        assertThat(catalog.activities(course, "고급 알고리즘").map { it.kind to it.title }).containsExactly(
            RefKind.PLMS_ACTIVITY to "공지사항", RefKind.PLMS_FILE to "7. ChordalGraphs & more", RefKind.PLMS_ASSIGNMENT to "HW6",
        )
        val board = """
            <tbody><tr class="">
              <td class="t-subject"><a href="https://plms.postech.ac.kr/mod/ubboard/article.php?keyfield&amp;keyword&amp;ls=15&amp;page=1&amp;bwid=97868&amp;id=232395">
                 <span class="badge badge-notice mr-1 ">N</span>
                 Midterm room change
              </a></td>
              <td class="t-date"><span title="2026-10-01 15:58:06">2026-10-01</span></td>
            </tr></tbody>
        """
        val posts = catalog.posts(board, "고급 알고리즘 · 공지사항")
        assertThat(posts.single()).isEqualTo(
            Ref(RefKind.PLMS_NOTICE, "Midterm room change", "plms:post/232395/97868", "고급 알고리즘 · 공지사항", Instant.parse("2026-10-01T06:58:06Z")),
        )
    }

    @Test
    fun `mail is searched live and can be read`() {
        TestMail.reset()
        client.post().uri("/api/v1/mail/connect").exchange().expectStatus().isOk
        val until = System.currentTimeMillis() + 10_000
        while (get("/api/v1/mail/account")["state"] != "connected") {
            check(System.currentTimeMillis() < until) { "not connected" }
            Thread.sleep(100)
        }
        TestMail.deliver("Lab meeting moved", "lab@postech.ac.kr", flagged = false)
        val hit = search("lab meeting").single { it["kind"] == "mail" }
        assertThat(hit["context"]).isEqualTo("Prof. Kim <prof@postech.ac.kr>")
        assertThat(hit["url"]).isEqualTo("mid:lab@postech.ac.kr")
        @Suppress("UNCHECKED_CAST")
        val message = withParam("/api/v1/mail/message", "id", "lab@postech.ac.kr") as Map<String, Any?>
        assertThat(message).containsEntry("subject", "Lab meeting moved").containsEntry("text", "Please reply.")
        assertThat(resolve(hit["url"] as String)).containsEntry("title", "Lab meeting moved")
    }

    @Test
    fun `old links are rewritten, and the feed shows mail and PLMS links as pages`() {
        val target = post("/api/v1/tasks", mapOf("title" to "Midterm"))
        val t = post(
            "/api/v1/tasks",
            mapOf(
                "title" to "Study [Midterm](https://assistant.example/#task/${target["id"]})",
                "description" to "See [mail](https://assistant.example/#mail/lab%40postech.ac.kr) and [HW6](https://plms.postech.ac.kr/mod/ubfile/view.php?id=101)",
                "due" to mapOf("date" to "2026-10-20"),
            ),
        )
        val e = post("/api/v1/events", mapOf("title" to "Review", "notes" to "[post](https://plms.postech.ac.kr/mod/ubboard/article.php?ls=15&bwid=9&id=55)", "startDate" to "2026-10-21"))
        assertThat(runBlocking { migration.migrate() }).isEqualTo(2)
        val task = get("/api/v1/tasks/${t["id"]}")
        assertThat(task["title"]).isEqualTo("Study [Midterm]($base/task/${target["id"]})")
        assertThat(task["description"]).isEqualTo("See [mail](mid:lab@postech.ac.kr) and [HW6](plms:mod/ubfile/101)")
        assertThat(get("/api/v1/events/${e["id"]}")["notes"]).isEqualTo("[post](plms:post/55/9)")
        assertThat(runBlocking { migration.migrate() }).isEqualTo(0)

        val feed = client.get().uri(get("/api/v1/ical/feed")["path"] as String).exchange().expectBody(String::class.java).returnResult().responseBody!!
            .replace("\r\n ", "")
        assertThat(feed).contains("$base/mail/lab%40postech.ac.kr", "https://plms.postech.ac.kr/mod/ubfile/view.php?id=101", "https://plms.postech.ac.kr/mod/ubboard/article.php?id=55&bwid=9")
            .doesNotContain("mid:", "plms:")
    }
}
