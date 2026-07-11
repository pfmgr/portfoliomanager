#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
EXPOSE_COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.expose.yaml"
RUNTIME_ENV="${ROOT_DIR}/.opencode/docker/sonar/runtime/analysis.env"

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

REPO_NAME="$(basename "${ROOT_DIR}")"
REPO_PREFIX="$(printf '%s' "${REPO_NAME}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
[[ -n "${REPO_PREFIX}" ]] || REPO_PREFIX="repo"
COMPOSE_PROJECT_NAME="${REPO_PREFIX}_sonar"
SONAR_PORT="${SONAR_PORT:-9000}"
export REPO_PREFIX SONAR_PORT

log "Stopping SonarQube stack (${COMPOSE_PROJECT_NAME})..."
if [[ "${REMOVE_VOLUMES}" == "true" ]]; then
  docker compose -f "${COMPOSE_FILE}" -f "${EXPOSE_COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" down -v
else
  docker compose -f "${COMPOSE_FILE}" -f "${EXPOSE_COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" down
fi
log "SonarQube stack stopped."
