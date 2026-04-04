#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/stack-env.sh"

TIMEOUT_SECONDS="${RUNNING_STACK_READY_TIMEOUT_SECONDS:-60}"
SLEEP_SECONDS="${RUNNING_STACK_READY_SLEEP_SECONDS:-1}"
CHECK_FRONTEND="${CHECK_FRONTEND:-true}"
CHECK_AUTH="${CHECK_AUTH:-true}"

check_frontend() {
  local status
  status="$(curl -sS -o /dev/null -w "%{http_code}" "${FRONTEND_BASE_URL}" || true)"
  [ "${status}" = "200" ] || [ "${status}" = "304" ]
}

check_auth() {
  local status
  status="$(curl -sS -o /dev/null -w "%{http_code}" "${AUTH_HEALTH_URL}" || true)"
  [ "${status}" = "200" ] || [ "${status}" = "204" ]
}

for ((i=1; i<=TIMEOUT_SECONDS; i++)); do
  frontend_ready=true
  auth_ready=true

  if [ "${CHECK_FRONTEND}" = "true" ]; then
    check_frontend || frontend_ready=false
  fi
  if [ "${CHECK_AUTH}" = "true" ]; then
    check_auth || auth_ready=false
  fi

  if [ "${frontend_ready}" = "true" ] && [ "${auth_ready}" = "true" ]; then
    printf 'Frontend: %s\n' "${FRONTEND_BASE_URL}"
    printf 'Auth: %s\n' "${AUTH_HEALTH_URL}"
    exit 0
  fi

  sleep "${SLEEP_SECONDS}"
done

printf 'Running stack not ready within %s seconds.\n' "${TIMEOUT_SECONDS}" >&2
printf 'Frontend URL: %s\n' "${FRONTEND_BASE_URL}" >&2
printf 'Auth health URL: %s\n' "${AUTH_HEALTH_URL}" >&2
exit 1
