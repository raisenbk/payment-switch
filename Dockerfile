# syntax=docker/dockerfile:1
# Builds one module of the repository: MODULE=app (default) or MODULE=mock-supplier.

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE=app
WORKDIR /workspace
COPY pom.xml .
COPY app/pom.xml app/
COPY mock-supplier/pom.xml mock-supplier/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE} dependency:go-offline
COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE} package -DskipTests \
    && cp ${MODULE}/target/*.jar /workspace/service.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /opt/app
COPY --from=build /workspace/service.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/app/app.jar"]
