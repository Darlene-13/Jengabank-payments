# syntax=docker/dockerfile:1.6
# JengaBank Payments (APP04) — competition image.
# Private, non-root, linux/amd64, no internet access at runtime.

# ---------- build stage: Maven produces the executable jar ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
# Warm the dependency cache in a separate layer so code changes rebuild fast.
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------- runtime stage: JRE only, non-root ----------
FROM eclipse-temurin:21-jre-jammy

# curl is only used by the container healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin jenga \
    && mkdir -p /app /tmp/jenga \
    && chown -R jenga:jenga /app /tmp/jenga

WORKDIR /app
COPY --from=build /build/target/app.jar /app/app.jar
RUN chown jenga:jenga /app/app.jar

USER 10001:10001

ENV SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp/jenga"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
