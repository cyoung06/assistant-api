package com.syeyoung.assistant.refs

import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Where the GitHub App is installed: the accounts whose repos, issues and PRs can be referenced. */
data class GitHubAccount(
    /** The App's ID and key are set on the server. */
    val configured: Boolean,
    val installations: List<String> = emptyList(),
    /** Where to install it on more accounts (e.g. an org). */
    val installUrl: String,
    val error: String? = null,
)

/**
 * GitHub repos, issues and pull requests as references, through a GitHub App (read-only Metadata,
 * Issues, Pull requests): `@` searches the repos it's installed on and the issues and PRs you're
 * involved in; a pasted GitHub link is named with its state. Links stay GitHub's own URLs, which
 * open anywhere. The App signs in with a short JWT from its private key and gets an hour-long
 * token per installation, refreshed as needed.
 */
@Component
class GitHubRefs(
    private val mapper: ObjectMapper,
    private val clock: Clock,
    props: AppProperties,
) : RefProvider {
    private val config = props.github
    private val api = config.apiUrl.trimEnd('/')
    private val web = config.webUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val key: PrivateKey? = runCatching {
        val der = Base64.getMimeDecoder().decode(config.appKey.replace(Regex("-----[A-Z ]+-----"), ""))
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }.getOrNull()?.takeIf { config.appId.isNotBlank() }
    private val lock = Mutex()

    private data class Installation(val id: Long, val login: String)

    @Volatile private var installations: Pair<Instant, List<Installation>>? = null
    private val tokens = mutableMapOf<Long, Pair<String, Instant>>()
    private val repos = mutableMapOf<Long, Pair<Instant, List<Ref>>>()

    // ---- the App -----------------------------------------------------------------------

    suspend fun account(): GitHubAccount {
        if (key == null) return GitHubAccount(false, installUrl = config.installUrl)
        return runCatching { GitHubAccount(true, installs().map { it.login }, config.installUrl) }
            .getOrElse { GitHubAccount(true, installUrl = config.installUrl, error = it.message) }
    }

    /** A JWT for the App itself, valid ten minutes (GitHub's limit), a minute in the past against clock skew. */
    private fun appJwt(): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val now = Instant.now(clock).epochSecond
        val head = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val body = b64.encodeToString("""{"iat":${now - 60},"exp":${now + 540},"iss":"${config.appId}"}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$head.$body".toByteArray()) }.sign()
        return "$head.$body.${b64.encodeToString(sig)}"
    }

    private suspend fun installs(): List<Installation> {
        installations?.let { (at, list) -> if (at.isAfter(Instant.now(clock).minus(CACHE))) return list }
        val list = request("GET", "/app/installations", appJwt()).toList().map { Installation(it.path("id").asLong(), it.path("account").path("login").asString("")) }
        installations = Instant.now(clock) to list
        return list
    }

    /** An installation's access token, made anew five minutes before the old one expires. */
    private suspend fun token(i: Installation): String = lock.withLock {
        tokens[i.id]?.let { (t, until) -> if (Instant.now(clock).isBefore(until.minus(Duration.ofMinutes(5)))) return t }
        val res = request("POST", "/app/installations/${i.id}/access_tokens", appJwt())
        val t = res.path("token").asString("")
        tokens[i.id] = t to Instant.parse(res.path("expires_at").asString())
        t
    }

    // ---- references --------------------------------------------------------------------

    override suspend fun search(query: String, limit: Int): List<Ref> = coroutineScope {
        if (key == null) return@coroutineScope emptyList()
        val q = query.trim().removePrefix("#")
        if (q.isEmpty()) return@coroutineScope emptyList()
        val words = q.lowercase().split(Regex("""\s+"""))
        installs().map { i ->
            async {
                val token = token(i)
                val search = URLEncoder.encode("$q involves:${i.login}", Charsets.UTF_8)
                val issues = async { request("GET", "/search/issues?q=$search&sort=updated&per_page=$limit", token).path("items").toList().map { issueRef(it) } }
                installedRepos(i, token).filter { r -> words.all { it in r.title.lowercase() } }.take(limit) + issues.await()
            }
        }.awaitAll().flatten()
    }

    override suspend fun resolve(url: String): Ref? {
        val path = url.removePrefix(web).takeIf { it != url } ?: return null
        val (owner, repo, what, number) = LINK.find(path)?.destructured ?: return null
        // The owner's installation sees private repos; anything else is read as public.
        val token = if (key == null) null else installs().find { it.login.equals(owner, ignoreCase = true) }?.let { token(it) }
        return runCatching {
            if (number.isEmpty()) repoRef(request("GET", "/repos/$owner/$repo", token))
            else issueRef(request("GET", "/repos/$owner/$repo/${if (what == "pull") "pulls" else "issues"}/$number", token))
        }.getOrNull()
    }

    /** The repos an installation can see; kept ten minutes. */
    private suspend fun installedRepos(i: Installation, token: String): List<Ref> {
        synchronized(repos) { repos[i.id] }?.let { (at, list) -> if (at.isAfter(Instant.now(clock).minus(CACHE))) return list }
        val list = request("GET", "/installation/repositories?per_page=100", token).path("repositories").toList().map { repoRef(it) }
        synchronized(repos) { repos[i.id] = Instant.now(clock) to list }
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

    private suspend fun request(method: String, path: String, token: String?): JsonNode {
        val req = HttpRequest.newBuilder(URI(api + path)).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString("") else HttpRequest.BodyPublishers.noBody()).build()
        val res = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        check(res.statusCode() in 200..299) { "GitHub answered HTTP ${res.statusCode()} for $path" }
        return mapper.readTree(res.body())
    }

    private companion object {
        val CACHE: Duration = Duration.ofMinutes(10)

        /** "/owner/repo", "/owner/repo/issues/12", "/owner/repo/pull/12" (anything after is ignored). */
        val LINK = Regex("""^/([\w.-]+)/([\w.-]+)(?:/(issues|pull)/(\d+))?(?:[/?#].*)?$""")
    }
}

@RestController
@RequestMapping("/api/v1/github")
class GitHubController(private val github: GitHubRefs) {

    /** Where the GitHub App is installed; its repos, issues and PRs can be referenced with @. */
    @GetMapping("/account")
    suspend fun githubAccount(): GitHubAccount = github.account()
}
