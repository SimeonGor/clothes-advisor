FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew ./
COPY gradle/ ./gradle/
COPY recommendation-service/build.gradle.kts recommendation-service/settings.gradle.kts ./
COPY recommendation-service/src/main/ ./src/main/
RUN chmod +x ./gradlew && ./gradlew --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process bootJar

FROM eclipse-temurin:25-jre

ARG APP_UID=10001
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    test "$APP_UID" -gt 0 && \
    (getent passwd "$APP_UID" > /dev/null || useradd --uid "$APP_UID" --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/build/libs/recommendation-service-0.0.1-SNAPSHOT.jar app.jar
USER ${APP_UID}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
