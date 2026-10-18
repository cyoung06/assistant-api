package com.syeyoung.assistant

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A real Radicale (CalDAV) server for tests, installed with pip into build/radicale-venv on first
 * use and started once per test run. Auth is off: any user name works. Set TEST_DAV_URL to use a
 * server you started yourself instead.
 */
object TestRadicale {
    const val VERSION = "3.8.3"

    val url: String by lazy { System.getenv("TEST_DAV_URL") ?: start() }

    private fun start(): String {
        val venv = File("build/radicale-venv").absoluteFile
        val python = File(venv, "bin/python")
        if (!File(venv, ".installed-$VERSION").exists()) {
            run(listOf("python3", "-m", "venv", venv.path))
            run(listOf(python.path, "-m", "pip", "install", "--quiet", "radicale==$VERSION"))
            File(venv, ".installed-$VERSION").writeText("")
        }
        val port = ServerSocket(0).use { it.localPort }
        val storage = Files.createTempDirectory("radicale").toFile()
        val process = ProcessBuilder(
            python.path, "-m", "radicale",
            "--server-hosts", "127.0.0.1:$port",
            "--auth-type", "none",
            "--rights-type", "owner_only",
            "--storage-filesystem-folder", storage.path,
            "--logging-level", "warning",
        ).redirectErrorStream(true).redirectOutput(File("build/radicale-test.log")).start()
        Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return "http://127.0.0.1:$port"
            check(process.isAlive) { "Radicale exited; see build/radicale-test.log" }
            Thread.sleep(100)
        }
        error("Radicale did not start; see build/radicale-test.log")
    }

    private fun run(cmd: List<String>) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
    }
}
