import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    // LLM access (quick add, capture) through Koog, over Spring's WebClient.
    implementation("ai.koog:prompt-executor-model:$koogVersion")
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
    implementation("ai.koog:http-client-spring-webclient:$koogVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // iCalendar recurrence expansion (any RRULE a calendar app may write).
    implementation("org.mnode.ical4j:ical4j:4.4.0")

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
