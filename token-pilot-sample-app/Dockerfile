# token-pilot-sample-app/Dockerfile

# ==========================================
# Runtime environment (Java 25); GitHub Actions performs the build.
# ==========================================
FROM eclipse-temurin:25-jre
WORKDIR /app

# Copy the JAR prebuilt by GitHub Actions into the container.
COPY token-pilot-sample-app/build/libs/*SNAPSHOT.jar ./app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
