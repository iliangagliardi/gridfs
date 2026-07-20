# syntax=docker/dockerfile:1
#
# Multi-stage build for the GridFS + Atlas Search demo.
#
# SIZE WARNING: tika-parsers-standard-package pulls in PDFBox, POI, the whole
# OOXML stack, jai-imageio, Bouncy Castle and friends. The fat jar lands in the
# 250-350 MB range on its own, so the finished runtime image is well north of
# 400 MB. That is expected and it is the price of parsing PDF/DOCX/XLSX/PPTX
# out of the box. If you need a small image, swap tika-parsers-standard-package
# for just the parser modules you actually use (that is a pom.xml change and
# outside the scope of this Dockerfile).

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM alone first and resolve dependencies. This layer is cached and
# only invalidated when pom.xml changes, so day-to-day source edits rebuild in
# seconds instead of re-downloading the Tika tree every time.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package \
 && cp target/gridfs-demo-*.jar /build/app.jar

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# wget is used by the compose healthcheck; keep the layer minimal otherwise.
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

# Non-root runtime user.
RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid spring --create-home spring

WORKDIR /app
COPY --from=build --chown=spring:spring /build/app.jar /app/app.jar

USER spring

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    MONGODB_URI="mongodb://mongodb:27017/gridfs_demo?directConnection=true" \
    PORT=8080

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
