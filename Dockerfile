# Multi-stage build
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src src

# Build with maven
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Download OpenTelemetry Java Agent
RUN wget https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.25.0/opentelemetry-javaagent.jar -O /app/opentelemetry-javaagent.jar

# Copy the built jar
COPY --from=build /app/target/appconfig-kafka-consumer-0.0.1-SNAPSHOT.jar /app/app.jar

ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
