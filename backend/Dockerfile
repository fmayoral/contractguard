# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml backend/checkstyle.xml backend/spotbugs-exclude.xml ./
RUN mvn -B -ntp dependency:go-offline -Dcheckstyle.skip -Dspotbugs.skip
COPY backend/src ./src
RUN mvn -B -ntp package -DskipTests -Dcheckstyle.skip -Dspotbugs.skip -Djacoco.skip

# Runtime stage: needs git for repository operations and the JDK so the
# consumer's Maven build runs on Java 21.
FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends git curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/contractguard-backend-*-SNAPSHOT.jar app.jar
COPY samples ./samples
COPY scripts/reset-demo.sh ./scripts/reset-demo.sh
RUN chmod +x scripts/reset-demo.sh
EXPOSE 8091
# Materialise the demo repository, then start bound to all interfaces so the
# frontend container can reach the API (the compose network stays internal).
CMD ["/bin/bash", "-c", "./scripts/reset-demo.sh && java -jar app.jar \
    --server.address=0.0.0.0 \
    --contractguard.workspace.roots[0]=/app/workspace \
    --contractguard.storage.directory=/app/data \
    --contractguard.specs.directory=/app/samples/openapi \
    --spring.datasource.url=jdbc:h2:file:/app/data/contractguard;MODE=PostgreSQL"]
