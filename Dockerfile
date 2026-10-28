# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml .
COPY app/pom.xml app/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl app dependency:go-offline
COPY app/src app/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl app package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /opt/app
COPY --from=build /workspace/app/target/payment-switch-app-*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/app/app.jar"]
