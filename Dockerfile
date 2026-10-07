FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre

RUN useradd --system --create-home appuser
WORKDIR /app
COPY --from=build /workspace/build/libs/clothes-advisor-0.0.1-SNAPSHOT.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
