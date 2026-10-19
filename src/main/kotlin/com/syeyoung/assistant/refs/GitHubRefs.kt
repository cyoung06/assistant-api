package com.syeyoung.assistant.refs

import com.syeyoung.assistant.common.TokenCipher
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class GitHubAccount(val connected: Boolean, val login: String? = null)

data class GitHubToken(val token: String)

/**
 * GitHub repos, issues and pull requests as references: `@` searches your repos and the issues and
 * PRs you're involved in (with a token pasted in Sources); a pasted GitHub link is named with its
 * state. Links stay GitHub's own URLs, which open anywhere.
 */
@Component
class GitHubRefs(
    private val db: DatabaseClient,
    private val cipher: TokenCipher,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    props: AppProperties,
) : RefProvider {
    private val api = props.github.apiUrl.trimEnd('/')
    private val web = props.github.webUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Volatile private var repos: Pair<Instant, List<Ref>>? = null

    // ---- the token ---------------------------------------------------------------------

    suspend fun account(): GitHubAccount = token()?.let { GitHubAccount(true, login()) } ?: GitHubAccount(false)

    suspend fun connect(token: String): GitHubAccount {
        val t = token.trim().ifEmpty { throw ValidationException("Paste a token") }
        val user = runCatching { get("/user", t) }.getOrNull() ?: throw ValidationException("GitHub didn't accept that token")
        val value = Base64.getEncoder().encodeToString(cipher.encrypt(t))
        db.sql("insert into settings (key, value) values ('github.token', :v) on conflict (key) do update set value = :v").bind("v", value).fetch().awaitRowsUpdated()
        db.sql("insert into settings (key, value) values ('github.login', :v) on conflict (key) do update set value = :v")
            .bind("v", user.path("login").asString("")).fetch().awaitRowsUpdated()
        repos = null
        return account()
    }

    suspend fun disconnect() {
        db.sql("delete from settings where key in ('github.token', 'github.login')").fetch().awaitRowsUpdated()
        repos = null
    }

    private suspend fun setting(key: String) = db.sql("select value from settings where key = :k").bind("k", key).map { r -> r.req<String>("value") }.awaitOneOrNull()

    private suspend fun token(): String? = setting("github.token")?.let { runCatching { cipher.decrypt(Base64.getDecoder().decode(it)) }.getOrNull() }

    private suspend fun login(): String? = setting("github.login")

    // ---- references --------------------------------------------------------------------

    override suspend fun search(query: String, limit: Int): List<Ref> = coroutineScope {
        val token = token() ?: return@coroutineScope emptyList()
        val q = query.trim().removePrefix("#")
        if (q.isEmpty()) return@coroutineScope emptyList()
        val issues = async {
            val login = login() ?: return@async emptyList()
            val search = URLEncoder.encode("$q involves:$login", Charsets.UTF_8)
            get("/search/issues?q=$search&sort=updated&per_page=$limit", token).path("items").toList().map { issueRef(it) }
        }
        val words = q.lowercase().split(Regex("""\s+"""))
        myRepos(token).filter { r -> words.all { it in r.title.lowercase() } }.take(limit) + issues.await()
    }

    override suspend fun resolve(url: String): Ref? {
        val m = LINK.find(url.removePrefix(web).let { if (it == url) return null else it }) ?: return null
        val (owner, repo, what, number) = m.destructured
        val token = token()
        return runCatching {
            if (number.isEmpty()) repoRef(get("/repos/$owner/$repo", token))
            else issueRef(get("/repos/$owner/$repo/${if (what == "pull") "pulls" else "issues"}/$number", token))
        }.getOrNull()
    }

    /** Repos you own, collaborate on or belong to through an org; kept ten minutes. */
    private suspend fun myRepos(token: String): List<Ref> {
        repos?.let { (at, list) -> if (at.isAfter(Instant.now(clock).minus(Duration.ofMinutes(10)))) return list }
        val list = get("/user/repos?per_page=100&sort=pushed", token).toList().map { repoRef(it) }
        repos = Instant.now(clock) to list
        return list
    }

    private fun repoRef(r: JsonNode) = Ref(
        RefKind.GITHUB_REPO, r.path("full_name").asString(""), r.path("html_url").asString(""),
        listOfNotNull("repo", "private".takeIf { r.path("private").asBoolean(false) }).joinToString(" · "),
        r.path("pushed_at").asString(null)?.let(Instant::parse),
    )

    /** An issue or PR (from search, the issues API or the pulls API): "owner/repo#12 Title", with its state. */
    private fun issueRef(i: JsonNode): Ref {
        val url = i.path("html_url").asString("")
        val pr = "/pull/" in url
        val repo = LINK.find(url.removePrefix(web))?.destructured?.let { (o, r) -> "$o/$r" }.orEmpty()
        val merged = !i.path("merged_at").isNull && !i.path("merged_at").isMissingNode ||
            !i.path("pull_request").path("merged_at").isNull && !i.path("pull_request").path("merged_at").isMissingNode
        val state = when {
            pr && merged -> "merged PR"
            pr -> "${i.path("state").asString("")} PR"
            else -> "${i.path("state").asString("")} issue"
        }
        return Ref(
            if (pr) RefKind.GITHUB_PR else RefKind.GITHUB_ISSUE, "$repo#${i.path("number").asLong()} ${i.path("title").asString("")}", url, state,
            i.path("updated_at").asString(null)?.let(Instant::parse),
        )
    }

    private suspend fun get(path: String, token: String?): JsonNode {
        val req = HttpRequest.newBuilder(URI(api + path)).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .apply { token?.let { header("Authorization", "Bearer $it") } }.GET().build()
        val res = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        check(res.statusCode() in 200..299) { "GitHub answered HTTP ${res.statusCode()}" }
        return mapper.readTree(res.body())
    }

    private companion object {
        /** "/owner/repo", "/owner/repo/issues/12", "/owner/repo/pull/12" (anything after is ignored). */
        val LINK = Regex("""^/([\w.-]+)/([\w.-]+)(?:/(issues|pull)/(\d+))?(?:[/?#].*)?$""")
    }
}

@RestController
@RequestMapping("/api/v1/github")
class GitHubController(private val github: GitHubRefs) {

    @GetMapping("/account")
    suspend fun githubAccount(): GitHubAccount = github.account()

    /** A personal access token (read-only is enough), so @ can search your repos, issues and PRs. */
    @PutMapping("/account")
    suspend fun connectGithub(@RequestBody req: GitHubToken): GitHubAccount = github.connect(req.token)

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun disconnectGithub() = github.disconnect()
}
