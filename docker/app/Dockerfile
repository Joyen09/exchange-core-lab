# Self-contained build: `docker compose up` works from a fresh clone with no local JDK.
#
# Stage 1 compiles with the Gradle wrapper pinned in the repository. Tests are skipped here
# because the build container has no Docker socket and the integration tests need Testcontainers;
# CI runs `./gradlew build` (tests included) on the host instead.
FROM gradle:8.14-jdk21 AS builder

# Optional trust anchors for networks that terminate TLS on an inspecting proxy. The directory
# ships empty, so this is a no-op on an ordinary connection; drop a PEM in it when the build
# host sits behind a corporate proxy (see "Development environment notes" in the README).
COPY docker/app/ca-certificates /tmp/extra-ca
RUN set -eu; \
    if ls /tmp/extra-ca/*.crt >/dev/null 2>&1; then \
        for cert in /tmp/extra-ca/*.crt; do \
            cp "$cert" /usr/local/share/ca-certificates/; \
            keytool -importcert -noprompt -alias "extra-$(basename "$cert" .crt)" \
                    -file "$cert" -cacerts -storepass changeit; \
        done; \
        update-ca-certificates; \
    fi

WORKDIR /workspace

# Dependency resolution is cached separately from source, so editing code does not re-download
# the world on every rebuild.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home /app app

WORKDIR /app
COPY --from=builder --chown=app:app /workspace/build/libs/*.jar /app/app.jar

USER app
EXPOSE 8080

# Container-aware heap sizing; the JVM otherwise assumes the host's memory.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
