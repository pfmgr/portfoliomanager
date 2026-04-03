#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
RUNTIME_ENV="${ROOT_DIR}/.opencode/docker/sonar/.runtime.env"

REMOVE_VOLUMES=true
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

REPO_PREFIX=""
COMPOSE_PROJECT_NAME=""

if [[ -f "${RUNTIME_ENV}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${RUNTIME_ENV}" 2>/dev/null || true
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

rm -f "${RUNTIME_ENV}"
