# =============================================================================
# Stage 1 — Build
# =============================================================================
# maven:3.9-eclipse-temurin-17 is a multi-arch image
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml first and resolve all dependencies in isolation.
# Docker caches this layer separately. As long as pom.xml does not change,
# Maven skips the download step on every rebuild — much faster CI builds.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and package. Tests are skipped here — they run in CI, not here.
COPY src ./src
RUN mvn clean package -DskipTests -B


# =============================================================================
# Stage 2 — Runtime
# =============================================================================
# eclipse-temurin:17-jre-jammy
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# Never run a service as root inside a container.
# groupadd / useradd are the correct commands on Debian/Ubuntu base images
# (Alpine uses addgroup / adduser — not available here).
RUN groupadd -r aviation && useradd -r -g aviation aviation

# Copy only the compiled JAR from the build stage — nothing else.
COPY --from=build /app/target/aviation-api-wrapper-1.0.0.jar app.jar

USER aviation

# Document the port — publishing is done by docker-compose, not here.
EXPOSE 8080

# from "docker stop", enabling Spring Boot's graceful shutdown.
ENTRYPOINT ["java", "-jar", "app.jar"]
