#!/usr/bin/env sh
# Runs Maven inside a JDK 21 container, for machines without a local JDK.
# Testcontainers reaches the host Docker daemon through the mounted socket.
#
#   ./scripts/mvn-docker.sh verify
#   ./scripts/mvn-docker.sh -pl app test -Dtest=FoundationIntegrationTest
set -eu

cd "$(dirname "$0")/.."

exec docker run --rm \
  -v "$PWD":/workspace -w /workspace \
  -v payment-switch-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-21 \
  ./mvnw -B "$@"
