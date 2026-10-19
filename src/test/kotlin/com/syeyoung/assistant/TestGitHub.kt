package com.syeyoung.assistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * GitHub's REST API for tests, as a GitHub App sees it: the App (a JWT signed with [privateKeyPem]'s
 * key) has one installation, on cyoung06, whose token sees two repos, a merged PR and an open issue.
 */
object TestGitHub {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

    /** The App's private key as GitHub hands it out, converted to PKCS#8. */
    val privateKeyPem: String = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"

    /** JWTs that were sent with a valid signature. */
    @Volatile var appCalls = 0

    val url: String by lazy {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.rawPath
            val auth = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
            val installation = auth == "inst-1"
            when {
                path == "/app/installations" && validJwt(auth) -> respond(ex, """[{"id":1,"account":{"login":"cyoung06"}}]""")
                path == "/app/installations/1/access_tokens" && ex.requestMethod == "POST" && validJwt(auth) ->
                    respond(ex, """{"token":"inst-1","expires_at":"2099-01-01T00:00:00Z"}""", 201)
                path.startsWith("/app") -> respond(ex, """{"message":"A JSON web token could not be decoded"}""", 401)
                path == "/installation/repositories" && installation ->
                    respond(ex, """{"repositories":[${repo("cyoung06/assistant", true)}, ${repo("cyoung06/tasks", false)}]}""")
                path == "/search/issues" && installation -> respond(ex, """{"items":[${pr()}, ${issue()}]}""")
                path == "/repos/cyoung06/assistant" && installation -> respond(ex, repo("cyoung06/assistant", true))
                path == "/repos/cyoung06/assistant" -> respond(ex, """{"message":"Not Found"}""", 404)
                path == "/repos/PLUS-POSTECH/tasks" -> respond(ex, repo("PLUS-POSTECH/tasks", false))
                path == "/repos/cyoung06/tasks/pulls/42" -> respond(ex, pr())
                path == "/repos/cyoung06/tasks/issues/7" -> respond(ex, issue())
                else -> respond(ex, """{"message":"Not Found"}""", 404)
            }
        }
        server.start()
        "http://127.0.0.1:${server.address.port}"
    }

    private fun validJwt(jwt: String?): Boolean {
        val parts = jwt?.split('.') ?: return false
        if (parts.size != 3) return false
        val ok = Signature.getInstance("SHA256withRSA").apply { initVerify(keys.public); update("${parts[0]}.${parts[1]}".toByteArray()) }
            .verify(Base64.getUrlDecoder().decode(parts[2]))
        val claims = String(Base64.getUrlDecoder().decode(parts[1]))
        return (ok && "\"iss\":\"12345\"" in claims).also { if (it) appCalls++ }
    }

    private fun repo(name: String, private: Boolean) =
        """{"full_name":"$name","html_url":"https://github.com/$name","private":$private,"pushed_at":"2026-10-18T00:00:00Z"}"""

    private fun pr() =
        """{"number":42,"title":"Triage page","state":"closed","html_url":"https://github.com/cyoung06/tasks/pull/42","merged_at":"2026-10-17T00:00:00Z","updated_at":"2026-10-17T00:00:00Z","pull_request":{"merged_at":"2026-10-17T00:00:00Z"}}"""

    private fun issue() =
        """{"number":7,"title":"Triage keyboard","state":"open","html_url":"https://github.com/cyoung06/tasks/issues/7","updated_at":"2026-10-16T00:00:00Z"}"""

    private fun respond(ex: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
