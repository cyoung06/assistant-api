package com.syeyoung.assistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** GitHub's REST API for tests: one user, two repos, a merged PR and an open issue; the token "good" is accepted. */
object TestGitHub {
    val url: String by lazy {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.rawPath
            val authed = ex.requestHeaders.getFirst("Authorization") == "Bearer good"
            when {
                path == "/user" && authed -> respond(ex, """{"login":"cyoung06"}""")
                path == "/user" -> respond(ex, """{"message":"Bad credentials"}""", 401)
                path == "/user/repos" && authed -> respond(ex, """[${repo("cyoung06/assistant", true)}, ${repo("PLUS-POSTECH/tasks", false)}]""")
                path == "/search/issues" && authed -> respond(ex, """{"items":[${pr()}, ${issue()}]}""")
                path == "/repos/PLUS-POSTECH/tasks" -> respond(ex, repo("PLUS-POSTECH/tasks", false))
                path == "/repos/PLUS-POSTECH/tasks/pulls/42" -> respond(ex, pr())
                path == "/repos/PLUS-POSTECH/tasks/issues/7" -> respond(ex, issue())
                else -> respond(ex, """{"message":"Not Found"}""", 404)
            }
        }
        server.start()
        "http://127.0.0.1:${server.address.port}"
    }

    private fun repo(name: String, private: Boolean) =
        """{"full_name":"$name","html_url":"https://github.com/$name","private":$private,"pushed_at":"2026-10-18T00:00:00Z"}"""

    private fun pr() =
        """{"number":42,"title":"Triage page","state":"closed","html_url":"https://github.com/PLUS-POSTECH/tasks/pull/42","merged_at":"2026-10-17T00:00:00Z","updated_at":"2026-10-17T00:00:00Z","pull_request":{"merged_at":"2026-10-17T00:00:00Z"}}"""

    private fun issue() =
        """{"number":7,"title":"Triage keyboard","state":"open","html_url":"https://github.com/PLUS-POSTECH/tasks/issues/7","updated_at":"2026-10-16T00:00:00Z"}"""

    private fun respond(ex: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
