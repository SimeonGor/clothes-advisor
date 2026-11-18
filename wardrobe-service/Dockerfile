# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew ./
COPY gradle/ ./gradle/
COPY observability/build.gradle.kts /observability/
COPY observability/src/main/ /observability/src/main/
COPY wardrobe-service/build.gradle.kts wardrobe-service/settings.gradle.kts ./
COPY wardrobe-service/src/main/ ./src/main/
RUN --mount=type=cache,id=clothes-advisor-gradle,target=/root/.gradle,sharing=locked \
    chmod +x ./gradlew && ./gradlew --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process bootJar

FROM eclipse-temurin:25-jre

ARG APP_UID=10001
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    test "$APP_UID" -gt 0 && \
    (getent passwd "$APP_UID" > /dev/null || useradd --uid "$APP_UID" --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/build/libs/wardrobe-service-0.0.1-SNAPSHOT.jar app.jar
USER ${APP_UID}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
