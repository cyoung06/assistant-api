# The shared core (core/, the assistant-core submodule) for this image's Linux: its library where
# JNA looks for it and its Kotlin bindings, as build.gradle.kts makes them with cargo elsewhere.
FROM rust:1.99.1-slim-bookworm AS core
WORKDIR /core
COPY core/ ./
RUN cargo build --release --lib && cargo build --lib \
 && cargo run --bin uniffi-bindgen -- generate --library target/debug/libassistant_core.so --language kotlin --no-format --out-dir /out/kotlin \
 && mkdir -p /out/native/linux-x86-64 && cp target/release/libassistant_core.so /out/native/linux-x86-64/

# Build
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Dependencies first, so they stay cached while only sources change.
RUN ./gradlew --no-daemon -q -PcorePrebuilt=/core dependencies > /dev/null
COPY --from=core /out /core
COPY src/main src/main
RUN ./gradlew --no-daemon -q -PcorePrebuilt=/core bootJar \
 && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination /app

# Run
FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER 10001
ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
