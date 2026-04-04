#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
STACK_ENV_FILE="${STACK_ENV_FILE:-${REPO_ROOT}/.env}"
STACK_COMPOSE_FILE="${STACK_COMPOSE_FILE:-${REPO_ROOT}/docker-compose.yml}"

load_env_file() {
  local env_file="$1"
  local line key value

  [ -f "${env_file}" ] || return 0

  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ''|'#'*)
        continue
        ;;
    esac

    if [[ ! "${line}" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      continue
    fi

    key="${line%%=*}"
    value="${line#*=}"

    if [ -n "${!key+x}" ]; then
      continue
    fi

    if [[ "${value}" =~ ^".*"$ ]] || [[ "${value}" =~ ^'.*'$ ]]; then
      value="${value:1:${#value}-2}"
    fi

    export "${key}=${value}"
  done < "${env_file}"
}

if [ -f "${STACK_ENV_FILE}" ]; then
  load_env_file "${STACK_ENV_FILE}"
fi

resolve_host() {
  case "${1:-}" in
    ""|"0.0.0.0"|"::"|"[::]")
      printf '127.0.0.1'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

ADMIN_SPRING_HOST="${ADMIN_SPRING_HOST:-$(resolve_host "${ADMIN_SPRING_BIND:-127.0.0.1}")}"
ADMIN_FRONTEND_HOST="${ADMIN_FRONTEND_HOST:-$(resolve_host "${ADMIN_FRONTEND_BIND:-127.0.0.1}")}"
ADMIN_SPRING_PORT="${ADMIN_SPRING_PORT:-8089}"
ADMIN_FRONTEND_PORT="${ADMIN_FRONTEND_PORT:-8080}"

AUTH_BASE_URL="${AUTH_BASE_URL:-http://${ADMIN_SPRING_HOST}:${ADMIN_SPRING_PORT}/auth}"
API_BASE_URL="${API_BASE_URL:-http://${ADMIN_SPRING_HOST}:${ADMIN_SPRING_PORT}/api}"
FRONTEND_BASE_URL="${FRONTEND_BASE_URL:-http://${ADMIN_FRONTEND_HOST}:${ADMIN_FRONTEND_PORT}}"
AUTH_HEALTH_URL="${AUTH_HEALTH_URL:-${AUTH_BASE_URL}/health}"
AUTH_TOKEN_URL="${AUTH_TOKEN_URL:-${AUTH_BASE_URL}/token}"
AUTH_LOGOUT_URL="${AUTH_LOGOUT_URL:-${AUTH_BASE_URL}/logout}"

export REPO_ROOT STACK_ENV_FILE
export STACK_COMPOSE_FILE
export ADMIN_USER ADMIN_PASS
export ADMIN_SPRING_HOST ADMIN_FRONTEND_HOST ADMIN_SPRING_PORT ADMIN_FRONTEND_PORT
export AUTH_BASE_URL API_BASE_URL FRONTEND_BASE_URL AUTH_HEALTH_URL AUTH_TOKEN_URL AUTH_LOGOUT_URL
