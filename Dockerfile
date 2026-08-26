# syntax=docker/dockerfile:1

# JVM bytecode is architecture-independent, so the build runs once on BuildKit's native platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS build

ARG SBT_VERSION=2.0.6
ARG SBT_SHA256=60ce78a50b726b5b332a5277e363d67c028f16a3a15157f78a416c0b2949bc6d
ADD https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz /tmp/sbt.tgz
RUN echo "${SBT_SHA256}  /tmp/sbt.tgz" | sha256sum -c - \
    && tar -xzf /tmp/sbt.tgz -C /usr/local \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt \
    && rm /tmp/sbt.tgz

WORKDIR /build
COPY project/ project/
COPY build.sbt ./
RUN sbt update

COPY src/main/ src/main/
RUN sbt assembly \
    && cp target/out/jvm/scala-3.8.4/dicechess-bot-gcp-onnx/dicechess-bot-gcp-onnx.jar /build/app.jar

# Pin the Ubuntu 24.04 runtime manifest so releases cannot silently drift between base images.
FROM eclipse-temurin:25-jre-noble@sha256:fbcf915c585659b30eb766ada4d6d7cfc9ec1040bf521e95bf61b10a25af73db

LABEL org.opencontainers.image.title="Dice Chess ONNX webhook bot" \
      org.opencontainers.image.description="ONNX expectimax Dice Chess bot with model pre-ranking, Star pruning, TT, and root rescoring" \
      org.opencontainers.image.url="https://github.com/fortemate/dicechess-bot-gcp-onnx" \
      org.opencontainers.image.source="https://github.com/fortemate/dicechess-bot-gcp-onnx" \
      org.opencontainers.image.documentation="https://github.com/fortemate/dicechess-bot-gcp-onnx#readme" \
      org.opencontainers.image.vendor="Fortemate" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.authors="Jegors Čemisovs" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre-noble"

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar /app/app.jar

ARG APP_VERSION=dev
ARG SOURCE_REVISION=unknown
ENV BOT_WRAPPER_VERSION=$APP_VERSION \
    SOURCE_REVISION=$SOURCE_REVISION \
    PORT=8080

USER app
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
