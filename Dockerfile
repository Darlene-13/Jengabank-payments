# syntax=docker/dockerfile:1
# ---------- 1. Frontend (React) ----------
FROM --platform=$BUILDPLATFORM node:22-slim AS web
WORKDIR /web
COPY jenga-frontend/package.json jenga-frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY jenga-frontend/ ./
RUN npm run build

# ---------- 2. Backend (Maven) ----------
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
# The cache mount keeps ~/.m2 between builds, so a pom change doesn't re-download everything.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=web /web/dist/ ./src/main/resources/static/
# Tests run in CI; the image build stays fast and deterministic.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && find target -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' ! -name '*.original' -exec cp {} /src/app.jar \;

# ---------- 3. Runtime ----------
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jre-alpine AS runtime
RUN apk upgrade --no-cache \
 && addgroup -S -g 10001 app \
 && adduser  -S -u 10001 -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build --chown=10001:10001 /src/app.jar /app/app.jar

# JVM shares the 3 GB limit with PostgreSQL: cap the heap, die cleanly on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC -Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
