FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ ./gradle/
COPY wardrobe-service/build.gradle.kts ./wardrobe-service/
COPY config-server/build.gradle.kts ./config-server/
COPY discovery-server/build.gradle.kts ./discovery-server/
COPY api-gateway/build.gradle.kts ./api-gateway/
COPY recommendation-service/build.gradle.kts ./recommendation-service/
COPY account-service/build.gradle.kts ./account-service/
COPY account-service/src/main/ ./account-service/src/main/
RUN chmod +x ./gradlew && ./gradlew --no-daemon --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process :account-service:bootJar

FROM eclipse-temurin:25-jre

ARG APP_UID=10001
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    test "$APP_UID" -gt 0 && \
    (getent passwd "$APP_UID" > /dev/null || useradd --uid "$APP_UID" --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/account-service/build/libs/account-service-0.0.1-SNAPSHOT.jar app.jar
USER ${APP_UID}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
