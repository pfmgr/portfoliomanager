#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
RUNTIME_DIR="${ROOT_DIR}/.opencode/docker/sonar/runtime"

REMOVE_VOLUMES=false
FORCE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --volumes)
      REMOVE_VOLUMES=true
      shift
      ;;
    --force)
      FORCE=true
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 4
      ;;
  esac
done

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

if [[ "${REMOVE_VOLUMES}" == "true" && "${FORCE}" != "true" ]]; then
  echo "Refusing to delete Sonar volumes without --force. Volume deletion requires explicit human approval." >&2
  exit 4
fi

if [[ "${REMOVE_VOLUMES}" == "true" ]]; then
  docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" down -v --remove-orphans
else
  docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" down --remove-orphans
fi

if [[ "${REMOVE_VOLUMES}" == "true" ]]; then
  volumes=(
    "${REPO_PREFIX}_sonar_data"
    "${REPO_PREFIX}_sonar_extensions"
    "${REPO_PREFIX}_sonar_logs"
    "${REPO_PREFIX}_sonar_db_data"
  )

  for volume in "${volumes[@]}"; do
    if [[ "${FORCE}" == "true" ]]; then
      docker volume rm -f "${volume}" >/dev/null 2>&1 || true
    else
      if ! docker volume rm -f "${volume}" >/dev/null 2>&1; then
        echo "Failed to remove volume: ${volume}" >&2
        exit 4
      fi
    fi
  done
fi

# Runtime credentials are coupled to the persistent Sonar installation. A
# non-destructive recovery must not strand existing users by deleting them.
if [[ "${REMOVE_VOLUMES}" == "true" ]]; then
  rm -rf "${RUNTIME_DIR}"
fi
