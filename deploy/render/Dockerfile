# Render build: repo root is the build context (unlike the HF Space
# Dockerfile, which clones from GitHub). Same runtime either way.
FROM docker.io/maven:3.9-eclipse-temurin-21 AS build
COPY pom.xml /app/pom.xml
COPY src /app/src
WORKDIR /app
RUN mvn -q package -DskipTests

FROM docker.io/postgres:16-bookworm
COPY --from=docker.io/eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH
COPY --from=build /app/target/flowforge-*.jar /app/app.jar
COPY deploy/hf/start.sh /start.sh
RUN chmod +x /start.sh
CMD ["/start.sh"]
