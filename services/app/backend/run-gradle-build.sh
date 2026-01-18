#!/usr/bin/env bash
set -euo pipefail
BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DB_PORT=5439
export DB_NAME=mydatabase
export DB_URL=jdbc:postgresql://localhost:$DB_PORT/$DB_NAME
export DB_USER=myuser
export DB_PASS=secret
COMPOSE_FILE="${BACKEND_DIR}/int-test-env/compose.yaml"

cleanup() {
  docker compose -f "${COMPOSE_FILE}"  down
}

trap cleanup EXIT

docker compose -f "${COMPOSE_FILE}" up -d --build

(cd "${BACKEND_DIR}" && ./gradlew clean build)
