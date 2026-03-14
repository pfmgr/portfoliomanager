#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
RUNTIME_ENV="${ROOT_DIR}/.opencode/docker/sonar/.runtime.env"

PORT_OVERRIDE=""
TIMEOUT_SECONDS=180
QUIET=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)
      PORT_OVERRIDE="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
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

PYTHON_BIN=""
if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="python"
else
  echo "Python is required to detect a free port." >&2
  exit 4
fi

REPO_NAME="$(basename "${ROOT_DIR}")"
REPO_PREFIX="$(printf '%s' "${REPO_NAME}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
if [[ -z "${REPO_PREFIX}" ]]; then
  REPO_PREFIX="repo"
fi

COMPOSE_PROJECT_NAME="${REPO_PREFIX}_sonar"

if [[ -z "${PORT_OVERRIDE}" && -f "${RUNTIME_ENV}" ]]; then
  EXISTING_COMPOSE_PROJECT_NAME=""
  EXISTING_SONAR_PORT=""
  EXISTING_SONAR_URL=""
  set -a
  # shellcheck disable=SC1090
  source "${RUNTIME_ENV}"
  set +a
  EXISTING_COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
  EXISTING_SONAR_PORT="${SONAR_PORT:-}"
  EXISTING_SONAR_URL="${SONAR_URL:-}"
  COMPOSE_PROJECT_NAME="${REPO_PREFIX}_sonar"

  if [[ "${EXISTING_COMPOSE_PROJECT_NAME}" == "${COMPOSE_PROJECT_NAME}" && -n "${EXISTING_SONAR_PORT}" && -n "${EXISTING_SONAR_URL}" ]]; then
    existing_container_id="$(docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" ps -q sonarqube 2>/dev/null || true)"
    if [[ -n "${existing_container_id}" ]]; then
      if curl -sf "${EXISTING_SONAR_URL}/api/system/status" | grep -q '"status":"UP"'; then
        echo "SONAR_PORT=${EXISTING_SONAR_PORT}"
        echo "SONAR_URL=${EXISTING_SONAR_URL}"
        exit 0
      fi
    fi
  fi
fi

is_port_free() {
  local port="$1"
  "${PYTHON_BIN}" - "$port" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket()
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(("127.0.0.1", port))
except OSError:
    sys.exit(1)
finally:
    sock.close()
PY
}

SONAR_PORT="${PORT_OVERRIDE}"
if [[ -z "${SONAR_PORT}" ]]; then
  for port in $(seq 9000 9100); do
    if is_port_free "${port}"; then
      SONAR_PORT="${port}"
      break
    fi
  done
fi

if [[ -z "${SONAR_PORT}" ]]; then
  echo "No free SonarQube port found." >&2
  exit 2
fi

SONAR_URL="http://127.0.0.1:${SONAR_PORT}"
SONAR_ADMIN_PASSWORD="${SONAR_ADMIN_PASSWORD:-admin}"

cat > "${RUNTIME_ENV}" <<EOF
REPO_PREFIX=${REPO_PREFIX}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}
SONAR_PORT=${SONAR_PORT}
SONAR_URL=${SONAR_URL}
EOF

export REPO_PREFIX SONAR_PORT SONAR_ADMIN_PASSWORD

log "Starting SonarQube stack (${COMPOSE_PROJECT_NAME}) on port ${SONAR_PORT}..."
if ! docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" up -d; then
  echo "Failed to start SonarQube stack." >&2
  exit 4
fi

deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  if curl -sf "${SONAR_URL}/api/system/status" | grep -q '"status":"UP"'; then
    log "SonarQube is UP."
    echo "SONAR_PORT=${SONAR_PORT}"
    echo "SONAR_URL=${SONAR_URL}"
    exit 0
  fi
  sleep 2
done

echo "SonarQube did not become ready within ${TIMEOUT_SECONDS}s." >&2
docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" logs --tail=200 sonarqube || true
docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" logs --tail=200 sonar_db || true
exit 3
