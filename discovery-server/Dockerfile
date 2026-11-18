# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew ./
COPY gradle/ ./gradle/
COPY observability/build.gradle.kts /observability/
COPY observability/src/main/ /observability/src/main/
COPY discovery-server/build.gradle.kts discovery-server/settings.gradle.kts ./
COPY discovery-server/src/main/ ./src/main/
RUN --mount=type=cache,id=clothes-advisor-gradle,target=/root/.gradle,sharing=locked \
    chmod +x ./gradlew && ./gradlew --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process bootJar

FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    (getent passwd 10001 > /dev/null || useradd --uid 10001 --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/build/libs/discovery-server-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
