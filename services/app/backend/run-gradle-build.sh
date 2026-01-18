#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export DB_PORT=5439
export DB_NAME=mydatabase
export DB_URL=jdbc:postgresql://localhost:$DB_PORT/$DB_NAME
export DB_USER=myuser
export DB_PASS=secret
COMPOSE_FILE="${ROOT_DIR}/backend/int-test-env/compose.yaml"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/test-results-tmp}"
AUTH_HEALTH_URL="${AUTH_HEALTH_URL:-http://127.0.0.1:18089/auth/login}"

cleanup() {
  docker compose -f "${COMPOSE_FILE}"  down
}

trap cleanup EXIT

docker compose -f "${COMPOSE_FILE}" up -d --build

./gradlew clean build
