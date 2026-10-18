# Build
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Dependencies first, so they stay cached while only sources change.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon -q bootJar \
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
