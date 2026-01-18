#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_NAME="portfoliomanager_e2e"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
E2E_BASE_URL="${E2E_BASE_URL:-http://127.0.0.1:18090}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/test-results-tmp}"
AUTH_HEALTH_URL="${AUTH_HEALTH_URL:-http://127.0.0.1:18089/auth/login}"

cleanup() {
  docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" down
}

trap cleanup EXIT

docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" up -d --build

frontend_ready=false
backend_ready=false
for _ in {1..60}; do
  if curl -sf "${E2E_BASE_URL}" > /dev/null; then
    frontend_ready=true
  fi
  status_code="$(curl -s -o /dev/null -w "%{http_code}" "${AUTH_HEALTH_URL}" || true)"
  if [ "${status_code}" != "000" ] && [ "${status_code}" -lt 500 ]; then
    backend_ready=true
  fi
  if [ "${frontend_ready}" = true ] && [ "${backend_ready}" = true ]; then
    break
  fi
  sleep 1
done

if [ "${frontend_ready}" != true ] || [ "${backend_ready}" != true ]; then
  echo "E2E stack did not become ready in time." >&2
  exit 1
fi

cd "${ROOT_DIR}"
E2E_BASE_URL="${E2E_BASE_URL}" npx --no-install playwright test --output "${OUTPUT_DIR}"
