FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY gradlew ./
COPY gradle/ ./gradle/
COPY api-gateway/build.gradle.kts api-gateway/settings.gradle.kts ./
COPY api-gateway/src/main/ ./src/main/
RUN chmod +x ./gradlew && ./gradlew --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process bootJar

FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    (getent passwd 10001 > /dev/null || useradd --uid 10001 --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/build/libs/api-gateway-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
