FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process :config-server:bootJar

FROM eclipse-temurin:25-jre

ARG APP_UID=10001
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    test "$APP_UID" -gt 0 && \
    (getent passwd "$APP_UID" > /dev/null || useradd --uid "$APP_UID" --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/config-server/build/libs/config-server-0.0.1-SNAPSHOT.jar app.jar
USER ${APP_UID}
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
