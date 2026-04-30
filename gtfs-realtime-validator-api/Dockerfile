# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Parent + all module poms first so Maven can cache dependencies independently of source changes.
# The webapp pom is included only so the reactor parses; "-pl api -am" never builds it.
COPY pom.xml .
COPY gtfs-realtime-validator-lib/pom.xml gtfs-realtime-validator-lib/pom.xml
COPY gtfs-realtime-validator-webapp/pom.xml gtfs-realtime-validator-webapp/pom.xml
COPY gtfs-realtime-validator-api/pom.xml gtfs-realtime-validator-api/pom.xml

RUN mvn -B -pl gtfs-realtime-validator-api -am -DskipTests dependency:go-offline

COPY gtfs-realtime-validator-lib/src gtfs-realtime-validator-lib/src
COPY gtfs-realtime-validator-api/src gtfs-realtime-validator-api/src

RUN mvn -B -T 1C -pl gtfs-realtime-validator-api -am -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# tini gives us a real PID 1 (proper signal forwarding + zombie reaping).
# wget is provided by Alpine's BusyBox and is used by HEALTHCHECK below.
RUN apk add --no-cache tini && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/gtfs-realtime-validator-api/target/gtfs-realtime-validator-api-*.jar /app/app.jar
USER app:app

# EXPOSE reflects the default PORT only; if PORT is overridden at runtime,
# `docker run -p <host>:<PORT>` must use the same value.
ENV PORT=8090
EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O - "http://localhost:${PORT}/health" >/dev/null || exit 1

ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "/app/app.jar"]
