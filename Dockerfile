# FlowForge on Hugging Face Spaces (Docker SDK) or any container host.
#
# Multi-stage: Maven builds the boot jar, then only a JRE + the jar ship.
# The container listens on $SERVER_PORT (7860 = the Spaces convention).
# Postgres is NOT bundled: point FF_DB_URL at a managed instance
# (Neon, Supabase, RDS, ...) and Flyway migrates on boot.
#
#   docker build -t flowforge .
#   docker run -p 7860:7860 \
#     -e FF_DB_URL=jdbc:postgresql://host:5432/flowforge \
#     -e FF_DB_USER=flowforge -e FF_DB_PASSWORD=... \
#     flowforge

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/flowforge-1.0.0.jar app.jar

# Spaces convention; override locally with -e SERVER_PORT=8080.
ENV SERVER_PORT=7860
# The embedded worker talks to this server; keep host+port in sync.
ENV FLOWFORGE_WORKER_SERVERURL=http://localhost:7860
EXPOSE 7860

CMD ["java", "-jar", "app.jar"]
