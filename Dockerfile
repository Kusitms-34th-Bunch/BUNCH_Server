FROM eclipse-temurin:21-jre-jammy

RUN useradd --system --create-home --user-group app
WORKDIR /app
COPY --chown=app:app build/libs/app.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
