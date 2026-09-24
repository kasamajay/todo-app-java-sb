# Production image. The fat jar is built in a throwaway Maven/JDK stage, then
# *extracted into layers* (Spring Boot's jarmode=tools) so each layer becomes
# its own Docker image layer:
#
#   dependencies/           lib/*.jar - the 30 third-party jars (~18MB), change rarely
#   spring-boot-loader/     (empty in this extracted layout)
#   snapshot-dependencies/  lib/*-SNAPSHOT.jar (none today)
#   application/            app.jar - our classes + resources (~100KB), change every commit
#
# A code-only change therefore rebuilds and pushes only the small application
# layer instead of the whole 20MB fat jar. The extracted layout is a thin
# app.jar whose manifest Class-Path lists lib/*.jar, run by the plain JVM
# classpath (no nested-jar launcher). See decisions/0015.
# (Dev still uses Dockerfile.dev + devtools restarts.)

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests run in CI / the dev image (mvn test), not here.
RUN mvn -q -B package -DskipTests \
 && java -Djarmode=tools -jar target/app.jar extract --layers --destination /extracted \
 && mkdir -p /extracted/dependencies /extracted/spring-boot-loader /extracted/snapshot-dependencies /extracted/application

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
# Least-frequently-changing layers first, so Docker's layer cache (and a
# registry's layer dedupe) reuse everything above the application layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

EXPOSE 8080

# One JVM process: the JSON stores keep in-memory state and assume a single
# writer (decisions/0004, 0014).
CMD ["java", "-jar", "/app/app.jar"]
