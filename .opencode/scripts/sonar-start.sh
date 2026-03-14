#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
RUNTIME_ENV="${ROOT_DIR}/.opencode/docker/sonar/.runtime.env"

PORT_OVERRIDE=""
TIMEOUT_SECONDS=180
QUIET=false
DEFAULT_QUALITY_GATE="${SONAR_DEFAULT_QUALITY_GATE:-PortfolioManager Default}"
CONFIGURE_DEFAULT_QUALITY_GATE=true
CREATE_DEFAULT_QUALITY_GATE="${SONAR_CREATE_DEFAULT_QUALITY_GATE:-true}"

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
    --default-gate)
      DEFAULT_QUALITY_GATE="$2"
      shift 2
      ;;
    --skip-default-gate)
      CONFIGURE_DEFAULT_QUALITY_GATE=false
      shift
      ;;
    --skip-default-gate-create)
      CREATE_DEFAULT_QUALITY_GATE=false
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

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

ensure_quality_gate_condition() {
  local gate_name="$1"
  local metric="$2"
  local op="$3"
  local error="$4"
  local gate_show_json="$5"

  local exists
  exists="$(${PYTHON_BIN} -c 'import json,sys
payload=sys.argv[1] if len(sys.argv)>1 else ""
metric=sys.argv[2] if len(sys.argv)>2 else ""
op=sys.argv[3] if len(sys.argv)>3 else ""
error=sys.argv[4] if len(sys.argv)>4 else ""
data=json.loads(payload) if payload else {}
conditions=data.get("conditions",[])
def as_str(v):
    return "" if v is None else str(v)
print("1" if any(c.get("metric")==metric and c.get("op")==op and as_str(c.get("error"))==error for c in conditions) else "")' "${gate_show_json}" "${metric}" "${op}" "${error}" 2>/dev/null || true)"

  if [[ -n "${exists}" ]]; then
    return 0
  fi

  if curl -sf -u "admin:${SONAR_ADMIN_PASSWORD}" -X POST \
    "${SONAR_URL}/api/qualitygates/create_condition" \
    --data-urlencode "gateName=${gate_name}" \
    --data-urlencode "metric=${metric}" \
    --data-urlencode "op=${op}" \
    --data-urlencode "error=${error}" >/dev/null; then
    log "Added quality gate condition: ${metric} ${op} ${error}."
    return 0
  fi

  log "Warning: failed to add quality gate condition ${metric} ${op} ${error}."
  return 0
}

ensure_default_quality_gate_policy() {
  local gate_name="$1"
  local gate_show_json

  gate_show_json="$(curl -sf -u "admin:${SONAR_ADMIN_PASSWORD}" -G \
    "${SONAR_URL}/api/qualitygates/show" \
    --data-urlencode "name=${gate_name}" || true)"
  if [[ -z "${gate_show_json}" ]]; then
    log "Warning: unable to read gate '${gate_name}' details; skipping policy condition setup."
    return 0
  fi

  ensure_quality_gate_condition "${gate_name}" "new_reliability_rating" "GT" "1" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_security_rating" "GT" "1" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_maintainability_rating" "GT" "1" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_coverage" "LT" "80" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_duplicated_lines_density" "GT" "3" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_blocker_violations" "GT" "0" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_critical_violations" "GT" "0" "${gate_show_json}"
}

configure_default_quality_gate() {
  if [[ "${CONFIGURE_DEFAULT_QUALITY_GATE}" != "true" ]]; then
    return 0
  fi

  if [[ -z "${DEFAULT_QUALITY_GATE}" ]]; then
    log "Skipping default quality gate setup: empty gate name."
    return 0
  fi

  GATES_JSON="$(curl -sf -u "admin:${SONAR_ADMIN_PASSWORD}" "${SONAR_URL}/api/qualitygates/list" || true)"
  if [[ -z "${GATES_JSON}" ]]; then
    log "Warning: unable to read quality gates; skipping default gate setup."
    return 0
  fi

  GATE_EXISTS="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
data=json.loads(payload) if payload else {};\
target=sys.argv[2] if len(sys.argv)>2 else "";\
print("1" if any(g.get("name")==target for g in data.get("qualitygates",[])) else "")' "${GATES_JSON}" "${DEFAULT_QUALITY_GATE}" 2>/dev/null || true)"

  if [[ -z "${GATE_EXISTS}" ]]; then
    if is_true "${CREATE_DEFAULT_QUALITY_GATE}"; then
      if curl -sf -u "admin:${SONAR_ADMIN_PASSWORD}" -X POST \
        "${SONAR_URL}/api/qualitygates/create" \
        --data-urlencode "name=${DEFAULT_QUALITY_GATE}" >/dev/null; then
        log "Created quality gate '${DEFAULT_QUALITY_GATE}'."
        GATE_EXISTS="1"
      else
        log "Warning: failed to create quality gate '${DEFAULT_QUALITY_GATE}'."
      fi
    fi
  fi

  if [[ -z "${GATE_EXISTS}" ]]; then
    log "Warning: quality gate '${DEFAULT_QUALITY_GATE}' not found; skipping default gate setup."
    return 0
  fi

  ensure_default_quality_gate_policy "${DEFAULT_QUALITY_GATE}"

  CURRENT_DEFAULT_GATE="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
data=json.loads(payload) if payload else {};\
print(next((g.get("name","") for g in data.get("qualitygates",[]) if g.get("isDefault")),""))' "${GATES_JSON}" 2>/dev/null || true)"

  if [[ "${CURRENT_DEFAULT_GATE}" == "${DEFAULT_QUALITY_GATE}" ]]; then
    log "Default quality gate already set to '${DEFAULT_QUALITY_GATE}'."
    return 0
  fi

  GATE_ID="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
data=json.loads(payload) if payload else {};\
target=sys.argv[2] if len(sys.argv)>2 else "";\
print(next((str(g.get("id","")) for g in data.get("qualitygates",[]) if g.get("name")==target),""))' "${GATES_JSON}" "${DEFAULT_QUALITY_GATE}" 2>/dev/null || true)"

  if curl -sf -u "admin:${SONAR_ADMIN_PASSWORD}" -X POST \
    "${SONAR_URL}/api/qualitygates/set_as_default" \
    --data-urlencode "name=${DEFAULT_QUALITY_GATE}" >/dev/null; then
    log "Default quality gate set to '${DEFAULT_QUALITY_GATE}'."
    return 0
  fi

  if [[ -n "${GATE_ID}" ]] && curl -sf -u "admin:${SONAR_ADMIN_PASSWORD}" -X POST \
    "${SONAR_URL}/api/qualitygates/set_as_default" \
    --data-urlencode "id=${GATE_ID}" >/dev/null; then
    log "Default quality gate set to '${DEFAULT_QUALITY_GATE}'."
    return 0
  fi

  log "Warning: failed to set default quality gate '${DEFAULT_QUALITY_GATE}'."
}

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
    configure_default_quality_gate
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
