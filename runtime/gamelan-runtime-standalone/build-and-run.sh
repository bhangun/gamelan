#!/bin/bash

# Build and run script for Gamelan Standalone Runtime

set -e

echo "Building Gamelan Standalone Runtime..."

# Build the project
./mvnw clean package -DskipTests

echo "Building Docker image..."
docker build -t gamelan-standalone .

echo "Starting Gamelan Standalone Runtime..."
docker run -p 8080:8080 -p 9090:9090 --name gamelan-standalone-container gamelan-standalone

echo "Gamelan Standalone Runtime is now running!"
echo "Access the API at: http://localhost:8080"
echo "Access gRPC at: localhost:9090"