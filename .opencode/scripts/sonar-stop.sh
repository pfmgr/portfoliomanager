#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
RUNTIME_ENV="${ROOT_DIR}/.opencode/docker/sonar/.runtime.env"

REMOVE_VOLUMES=false
QUIET=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --volumes)
      REMOVE_VOLUMES=true
      shift
      ;;
    --quiet)
      QUIET=true
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 4
      ;;
  esac
done

log() {
  if [[ "${QUIET}" != "true" ]]; then
    echo "$@"
  fi
}

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Missing compose file: ${COMPOSE_FILE}" >&2
  exit 4
fi

REPO_PREFIX=""
COMPOSE_PROJECT_NAME=""

if [[ -f "${RUNTIME_ENV}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${RUNTIME_ENV}"
  set +a
fi

if [[ -z "${REPO_PREFIX:-}" ]]; then
  REPO_NAME="$(basename "${ROOT_DIR}")"
  REPO_PREFIX="$(printf '%s' "${REPO_NAME}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
  if [[ -z "${REPO_PREFIX}" ]]; then
    REPO_PREFIX="repo"
  fi
fi

if [[ -z "${COMPOSE_PROJECT_NAME:-}" ]]; then
  COMPOSE_PROJECT_NAME="${REPO_PREFIX}_sonar"
fi

log "Stopping SonarQube stack (${COMPOSE_PROJECT_NAME})..."
if [[ "${REMOVE_VOLUMES}" == "true" ]]; then
  docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" down -v
else
  docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" down
fi
log "SonarQube stack stopped."
