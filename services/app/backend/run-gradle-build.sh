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

docker compose -f "${COMPOSE_FILE}" up -d

for i in {1..30}; do
  if docker compose -f "${COMPOSE_FILE}" exec -T postgres pg_isready -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

(cd "${BACKEND_DIR}" && ./gradlew clean build)
