# syntax=docker/dockerfile:1

# ── build: the official Gradle image (Gradle 9.8.1 + JDK 21). Also used by the `test` compose service. ──────────
FROM gradle:9.8.1-jdk21 AS build
WORKDIR /src

# Dependencies first, so source changes don't re-download them.
COPY settings.gradle.kts build.gradle.kts ./
RUN --mount=type=cache,target=/home/gradle/.gradle gradle --no-daemon --quiet dependencies > /dev/null

COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle gradle --no-daemon --quiet installDist

# ── runtime: just a JRE and the built distribution. ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/build/install/mor-ledger /app
USER app
EXPOSE 8090
HEALTHCHECK --interval=3s --timeout=3s --start-period=20s --retries=20 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8090 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\"" <&3'
ENTRYPOINT ["/app/bin/mor-ledger"]
