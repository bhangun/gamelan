#!/bin/bash
export DOCKER_HOST=unix:///Users/bhangun/.docker/run/docker.sock
./mvnw quarkus:dev
