FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre

ARG APP_UID=10001
RUN test "$APP_UID" -gt 0 && \
    (getent passwd "$APP_UID" > /dev/null || useradd --uid "$APP_UID" --create-home appuser)
WORKDIR /app
COPY --from=build /workspace/build/libs/clothes-advisor-0.0.1-SNAPSHOT.jar app.jar
USER ${APP_UID}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
