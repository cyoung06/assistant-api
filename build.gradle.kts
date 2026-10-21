import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.syeyoung.assistant"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val koogVersion = "1.3.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.1.1")

    // LLM access (capture: reading screenshots and text on request) through Koog, over Spring's WebClient.
    implementation("ai.koog:prompt-executor-model:$koogVersion")
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
    implementation("ai.koog:http-client-spring-webclient:$koogVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // MCP server (for ChatGPT through OpenAI's Secure MCP Tunnel): the official Java SDK, Spring's WebFlux transport.
    implementation("io.modelcontextprotocol.sdk:mcp-core:2.0.0")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.0")
    implementation("org.springframework.ai:mcp-spring-webflux:2.0.1")

    // IMAP for the mail source (version from Spring Boot's dependency management).
    implementation("org.eclipse.angus:angus-mail")

    // iCalendar recurrence expansion (any RRULE a calendar app may write).
    implementation("org.mnode.ical4j:ical4j:4.4.0")

    // First-page previews of the drive's PDFs.
    implementation("org.apache.pdfbox:pdfbox:3.0.5")

    // The shared core (core/, assistant-core: notes' live sync, Yjs by yrs), through its UniFFI bindings.
    implementation("net.java.dev.jna:jna:5.17.0")

    // Compile scope for its jsonb type (io.r2dbc.postgresql.codec.Json).
    implementation("org.postgresql:r2dbc-postgresql")
    // Flyway runs migrations over JDBC.
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    // A real Postgres process for tests, no Docker needed.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.15.0"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // An in-memory IMAP server for the mail source's tests.
    testImplementation("com.icegreen:greenmail:2.1.14")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// Only the executable jar; the plain one would just confuse the Docker build.
tasks.jar {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The shared core (assistant-core, the core/ submodule), built with cargo for the machine this
// runs on: its library where JNA looks for it (<os>-<arch>/ on the classpath) and its Kotlin
// bindings by UniFFI. The image's build passes them in ready-made instead (-PcorePrebuilt=<dir>,
// with kotlin/ and native/ in it; see the Dockerfile).
abstract class Cargo : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val crate: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    protected fun cargo(vararg args: String) {
        // rustup's cargo, found here: the command is looked up on Gradle's own PATH, which may
        // find another Rust (Homebrew's) whose cargo would mix toolchains with rustup's rustc.
        val path = listOf("/opt/homebrew/opt/rustup/bin", "${System.getProperty("user.home")}/.cargo/bin") + System.getenv("PATH").split(":")
        val cargo = path.map { File(it, "cargo") }.first { it.canExecute() }
        exec.exec {
            workingDir = crate.get().asFile
            environment("PATH", path.joinToString(":"))
            commandLine(listOf(cargo.path) + args)
        }
    }

    /** The library's file name here, and JNA's resource directory for this machine. */
    @get:Internal
    protected val libraryFile = if (System.getProperty("os.name").startsWith("Mac")) "libassistant_core.dylib" else "libassistant_core.so"
    @get:Internal
    protected val jnaPrefix: String
        get() {
            val os = if (System.getProperty("os.name").startsWith("Mac")) "darwin" else "linux"
            val arch = when (val a = System.getProperty("os.arch")) { "amd64", "x86_64" -> "x86-64"; "arm64" -> "aarch64"; else -> a }
            return "$os-$arch"
        }
}

/** The core's library, optimised. */
abstract class CoreLibrary : Cargo() {
    @TaskAction
    fun build() {
        cargo("build", "--release", "--lib")
        val dir = output.get().asFile.resolve(jnaPrefix).apply { mkdirs() }
        crate.get().asFile.resolve("target/release/$libraryFile").copyTo(dir.resolve(libraryFile), overwrite = true)
    }
}

/** The core's Kotlin bindings, by UniFFI from a debug build (the release one is stripped of what it reads). */
abstract class CoreBindings : Cargo() {
    @TaskAction
    fun generate() {
        cargo("build", "--lib")
        cargo("run", "--bin", "uniffi-bindgen", "--", "generate", "--library", "target/debug/$libraryFile", "--language", "kotlin", "--no-format", "--out-dir", output.get().asFile.path)
    }
}

val corePrebuilt = providers.gradleProperty("corePrebuilt").orNull
if (corePrebuilt != null) {
    kotlin.sourceSets["main"].kotlin.srcDir("$corePrebuilt/kotlin")
    sourceSets["main"].resources.srcDir("$corePrebuilt/native")
} else {
    val coreSources = listOf("src", "vendor", "Cargo.toml", "Cargo.lock", "uniffi.toml").map { file("core/$it") }
    val coreLibrary = tasks.register<CoreLibrary>("coreLibrary") {
        crate.set(layout.projectDirectory.dir("core"))
        sources.from(coreSources)
        output.set(layout.buildDirectory.dir("generated/core/native"))
    }
    val coreBindings = tasks.register<CoreBindings>("coreBindings") {
        crate.set(layout.projectDirectory.dir("core"))
        sources.from(coreSources)
        output.set(layout.buildDirectory.dir("generated/core/kotlin"))
    }
    kotlin.sourceSets["main"].kotlin.srcDir(coreBindings)
    sourceSets["main"].resources.srcDir(coreLibrary)
}
