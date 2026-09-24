# Production image: the jar is built in a throwaway Maven/JDK stage; the
# runtime stage is a JRE-only Alpine image with just that jar - no Maven, no
# JDK/javac, no sources, no devtools (excluded from the packaged jar). See
# decisions/0013-production-mode-nginx-static-bundle.md.
# (Dev still uses Dockerfile.dev + devtools restarts.)

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests run in the dev image (docker compose run --rm api mvn test), not here.
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /src/target/app.jar /app/app.jar

EXPOSE 8080

# One JVM process: the JSON stores keep in-memory state and assume a single
# writer (decisions/0004, 0014).
CMD ["java", "-jar", "/app/app.jar"]
