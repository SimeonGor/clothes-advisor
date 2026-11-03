FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle/ ./gradle/
COPY chatgpt-token-refresher/build.gradle.kts chatgpt-token-refresher/settings.gradle.kts ./
COPY chatgpt-token-refresher/src/main/ ./src/main/
RUN chmod +x ./gradlew && ./gradlew --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process installDist

FROM eclipse-temurin:25-jre
ARG APP_UID=10001
RUN test "$APP_UID" -gt 0 && \
    (getent passwd "$APP_UID" > /dev/null || useradd --uid "$APP_UID" --create-home appuser) && \
    mkdir -p /run/chatgpt && chown "$APP_UID" /run/chatgpt && chmod 700 /run/chatgpt
COPY --from=build /workspace/build/install/chatgpt-token-refresher /app
USER ${APP_UID}
ENTRYPOINT ["/app/bin/chatgpt-token-refresher"]
