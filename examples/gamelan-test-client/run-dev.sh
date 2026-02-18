#!/bin/bash
export DOCKER_HOST=unix://~/.docker/run/docker.sock
./mvnw quarkus:dev
