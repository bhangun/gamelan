# Use Eclipse Temurin OpenJDK 21 JRE as the base image
FROM eclipse-temurin:21-jre-alpine

# Install necessary packages
RUN apk add --no-cache bash curl tar

# Create app directory
WORKDIR /app

# Copy the JAR file from target directory
COPY target/gamelan-runtime-standalone-*-runner.jar app.jar

# Expose ports
EXPOSE 8080 9090

# Create a non-root user for security
RUN addgroup -g 1001 -S quarkus && \
    adduser -u 1001 -S quarkus -G quarkus

# Change ownership of the app directory to the quarkus user
RUN chown -R 1001:1001 /app

# Switch to the non-root user
USER 1001

# Set the entrypoint
ENTRYPOINT ["java", "-jar", "app.jar"]

# Default command
CMD []