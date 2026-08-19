# Runtime-only image. The fat jar (built by `sbt assembly`) bundles the engine, the ONNX Runtime
# native lib, a five-entry opening-book sample, and a synthetic fallback model. The public image
# contains no trained weights or production book; deployments provide them privately at runtime.
# Pin both the Ubuntu family and the multi-arch manifest so the runtime cannot drift between builds.
FROM eclipse-temurin:25-jre-noble@sha256:fbcf915c585659b30eb766ada4d6d7cfc9ec1040bf521e95bf61b10a25af73db

WORKDIR /app
COPY target/out/jvm/scala-3.8.4/dicechess-bot-gcp-onnx/dicechess-bot-gcp-onnx.jar /app/app.jar

# Container platforms route requests to $PORT (default 8080); Main reads it. EXPOSE is documentation only.
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
