#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_NAME="portfoliomanager_e2e"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
E2E_BASE_URL="${E2E_BASE_URL:-http://127.0.0.1:18090}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/test-results-tmp}"
AUTH_HEALTH_URL="${AUTH_HEALTH_URL:-http://127.0.0.1:18089/auth/health}"
E2E_READY_TIMEOUT_SECONDS="${E2E_READY_TIMEOUT_SECONDS:-180}"

generate_secret() {
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
}

ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-$(generate_secret)}"
JWT_SECRET="${JWT_SECRET:-$(generate_secret)}"
JWT_JTI_HASH_SECRET="${JWT_JTI_HASH_SECRET:-$(generate_secret)}"

while [ "${JWT_SECRET}" = "${JWT_JTI_HASH_SECRET}" ]; do
  JWT_JTI_HASH_SECRET="$(generate_secret)"
done

if [ "${#JWT_SECRET}" -lt 32 ] || [ "${#JWT_JTI_HASH_SECRET}" -lt 32 ]; then
  echo "JWT secrets must be at least 32 characters." >&2
  exit 1
fi

export ADMIN_USER ADMIN_PASS JWT_SECRET JWT_JTI_HASH_SECRET AUTH_HEALTH_URL

cleanup() {
  docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" down
}

trap cleanup EXIT

docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" up -d --build

frontend_ready=false
backend_ready=false
for ((i=1; i<=E2E_READY_TIMEOUT_SECONDS; i++)); do
  if curl -sf "${E2E_BASE_URL}" > /dev/null; then
    frontend_ready=true
  fi
  status_code="$(curl -s -o /dev/null -w "%{http_code}" "${AUTH_HEALTH_URL}" || true)"
  if [ "${status_code}" = "200" ] || [ "${status_code}" = "204" ]; then
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
E2E_BASE_URL="${E2E_BASE_URL}" AUTH_HEALTH_URL="${AUTH_HEALTH_URL}" ADMIN_USER="${ADMIN_USER}" ADMIN_PASS="${ADMIN_PASS}" npx --no-install playwright test --output "${OUTPUT_DIR}"
