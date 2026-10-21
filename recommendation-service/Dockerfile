FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ ./gradle/
COPY wardrobe-service/build.gradle.kts ./wardrobe-service/
COPY config-server/build.gradle.kts ./config-server/
COPY discovery-server/build.gradle.kts ./discovery-server/
COPY api-gateway/build.gradle.kts ./api-gateway/
COPY recommendation-service/build.gradle.kts ./recommendation-service/
COPY recommendation-service/src/main/ ./recommendation-service/src/main/
RUN chmod +x ./gradlew && ./gradlew --no-daemon --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process :recommendation-service:bootJar

FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    (getent passwd 10001 > /dev/null || useradd --uid 10001 --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/recommendation-service/build/libs/recommendation-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
