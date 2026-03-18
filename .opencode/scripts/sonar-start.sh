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
REUSE_RUNNING=false

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
  EXISTING_SONAR_TOKEN=""
  EXISTING_SONAR_TOKEN_NAME=""
  set -a
  # shellcheck disable=SC1090
  source "${RUNTIME_ENV}"
  set +a
  EXISTING_COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-}"
  EXISTING_SONAR_PORT="${SONAR_PORT:-}"
  EXISTING_SONAR_URL="${SONAR_URL:-}"
  EXISTING_SONAR_TOKEN="${SONAR_TOKEN:-}"
  EXISTING_SONAR_TOKEN_NAME="${SONAR_TOKEN_NAME:-}"
  COMPOSE_PROJECT_NAME="${REPO_PREFIX}_sonar"

  if [[ "${EXISTING_COMPOSE_PROJECT_NAME}" == "${COMPOSE_PROJECT_NAME}" && -n "${EXISTING_SONAR_PORT}" && -n "${EXISTING_SONAR_URL}" ]]; then
    existing_container_id="$(docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" ps -q sonarqube 2>/dev/null || true)"
    if [[ -n "${existing_container_id}" ]]; then
      if curl -sf "${EXISTING_SONAR_URL}/api/system/status" | grep -q '"status":"UP"'; then
        SONAR_PORT="${EXISTING_SONAR_PORT}"
        SONAR_URL="${EXISTING_SONAR_URL}"
        REUSE_RUNNING=true
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

if [[ "${REUSE_RUNNING}" != "true" ]]; then
  SONAR_PORT="${PORT_OVERRIDE}"
fi
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
SONAR_ADMIN_USER="${SONAR_ADMIN_USER:-admin}"
SONAR_ADMIN_PASSWORD="${SONAR_ADMIN_PASSWORD:-admin}"
SONAR_TOKEN="${SONAR_TOKEN:-${SONAR_ADMIN_TOKEN:-${EXISTING_SONAR_TOKEN:-}}}"
SONAR_TOKEN_NAME="${SONAR_TOKEN_NAME:-${EXISTING_SONAR_TOKEN_NAME:-}}"

sonar_api() {
  curl -sf -H "Authorization: Bearer ${SONAR_TOKEN}" "$@"
}

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

write_runtime_env() {
  cat > "${RUNTIME_ENV}" <<EOF
REPO_PREFIX=${REPO_PREFIX}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}
SONAR_PORT=${SONAR_PORT}
SONAR_URL=${SONAR_URL}
SONAR_TOKEN=${SONAR_TOKEN}
SONAR_TOKEN_NAME=${SONAR_TOKEN_NAME}
EOF
}

token_is_valid() {
  if [[ -z "${SONAR_TOKEN}" ]]; then
    return 1
  fi
  sonar_api "${SONAR_URL}/api/qualitygates/list" >/dev/null 2>&1
}

generate_sonar_token() {
  local cookie_file
  local token_name
  local token_json
  local generated
  local xsrf_token

  cookie_file="$(mktemp)"
  if ! curl -sf -c "${cookie_file}" -X POST \
    "${SONAR_URL}/api/authentication/login" \
    --data-urlencode "login=${SONAR_ADMIN_USER}" \
    --data-urlencode "password=${SONAR_ADMIN_PASSWORD}" >/dev/null; then
    rm -f "${cookie_file}"
    log "Error: failed SonarQube login for token bootstrap."
    return 1
  fi

  token_name="opencode_${REPO_PREFIX}_$(date +%s%N)"
  xsrf_token="$(awk '$6=="XSRF-TOKEN" {print $7}' "${cookie_file}" || true)"
  if [[ -z "${xsrf_token}" ]]; then
    rm -f "${cookie_file}"
    log "Error: failed to obtain SonarQube XSRF token for API token generation."
    return 1
  fi
  token_json="$(curl -sf -b "${cookie_file}" -X POST \
    "${SONAR_URL}/api/user_tokens/generate" \
    -H "X-XSRF-TOKEN: ${xsrf_token}" \
    --data-urlencode "name=${token_name}" || true)"
  rm -f "${cookie_file}"

  generated="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else ""; data=json.loads(payload) if payload else {}; print(data.get("token", ""))' "${token_json}" 2>/dev/null || true)"
  if [[ -z "${generated}" ]]; then
    log "Error: failed to generate SonarQube token."
    return 1
  fi

  SONAR_TOKEN="${generated}"
  SONAR_TOKEN_NAME="${token_name}"
  log "Generated SonarQube token '${SONAR_TOKEN_NAME}'."
  return 0
}

ensure_sonar_token() {
  if token_is_valid; then
    return 0
  fi

  if [[ -n "${SONAR_TOKEN}" ]]; then
    log "Warning: existing SonarQube token is invalid; generating a fresh token."
  fi

  generate_sonar_token || return 1

  if ! token_is_valid; then
    log "Error: generated SonarQube token is not valid for API access."
    return 1
  fi

  return 0
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

  if sonar_api -X POST \
    "${SONAR_URL}/api/qualitygates/create_condition" \
    --data-urlencode "gateName=${gate_name}" \
    --data-urlencode "metric=${metric}" \
    --data-urlencode "op=${op}" \
    --data-urlencode "error=${error}" >/dev/null; then
    log "Added quality gate condition: ${metric} ${op} ${error}."
    return 0
  fi

  log "Error: failed to add quality gate condition ${metric} ${op} ${error}."
  return 1
}

remove_quality_gate_metric_conditions() {
  local gate_name="$1"
  local gate_show_json="$2"
  shift 2

  local delete_ids
  delete_ids="$(${PYTHON_BIN} -c 'import json,sys
payload=sys.argv[1] if len(sys.argv)>1 else ""
metrics=set(sys.argv[2:])
data=json.loads(payload) if payload else {}
for c in data.get("conditions",[]):
    metric=c.get("metric")
    cond_id=c.get("id")
    if metric in metrics and cond_id is not None:
        print(cond_id)
' "${gate_show_json}" "$@" 2>/dev/null || true)"

  if [[ -z "${delete_ids}" ]]; then
    return 0
  fi

  local cond_id
  while IFS= read -r cond_id; do
    [[ -z "${cond_id}" ]] && continue
    if ! sonar_api -X POST \
      "${SONAR_URL}/api/qualitygates/delete_condition" \
      --data-urlencode "id=${cond_id}" >/dev/null; then
      log "Error: failed to delete quality gate condition id ${cond_id} on '${gate_name}'."
      return 1
    fi
    log "Removed quality gate condition id ${cond_id}."
  done <<< "${delete_ids}"

  return 0
}

is_default_quality_gate_active() {
  local gate_name="$1"
  local gates_json

  gates_json="$(sonar_api "${SONAR_URL}/api/qualitygates/list" || true)"
  if [[ -z "${gates_json}" ]]; then
    return 1
  fi

  local active
  active="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
data=json.loads(payload) if payload else {};\
target=sys.argv[2] if len(sys.argv)>2 else "";\
print("1" if any(g.get("name")==target and g.get("isDefault") for g in data.get("qualitygates",[])) else "")' "${gates_json}" "${gate_name}" 2>/dev/null || true)"
  [[ -n "${active}" ]]
}

ensure_default_quality_gate_policy() {
  local gate_name="$1"
  local gate_show_json

  gate_show_json="$(sonar_api -G \
    "${SONAR_URL}/api/qualitygates/show" \
    --data-urlencode "name=${gate_name}" || true)"
  if [[ -z "${gate_show_json}" ]]; then
    log "Warning: unable to read gate '${gate_name}' details; skipping policy condition setup."
    return 0
  fi

  remove_quality_gate_metric_conditions "${gate_name}" "${gate_show_json}" \
    "new_reliability_rating" \
    "new_security_rating" \
    "new_maintainability_rating" \
    "new_issues" \
    "new_violations" \
    "new_coverage" \
    "new_duplicated_lines_density" \
    "new_security_hotspots_reviewed" \
    "new_blocker_violations" \
    "new_critical_violations" \
    "new_major_violations" \
    "new_minor_violations"

  gate_show_json="$(sonar_api -G \
    "${SONAR_URL}/api/qualitygates/show" \
    --data-urlencode "name=${gate_name}" || true)"
  if [[ -z "${gate_show_json}" ]]; then
    log "Error: unable to refresh gate '${gate_name}' details after condition cleanup."
    return 1
  fi

  ensure_quality_gate_condition "${gate_name}" "new_coverage" "LT" "80" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_duplicated_lines_density" "GT" "3" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_blocker_violations" "GT" "0" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_critical_violations" "GT" "0" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_major_violations" "GT" "0" "${gate_show_json}"
  ensure_quality_gate_condition "${gate_name}" "new_minor_violations" "GT" "0" "${gate_show_json}"
}

configure_default_quality_gate() {
  if [[ "${CONFIGURE_DEFAULT_QUALITY_GATE}" != "true" ]]; then
    return 0
  fi

  if [[ -z "${DEFAULT_QUALITY_GATE}" ]]; then
    log "Skipping default quality gate setup: empty gate name."
    return 0
  fi

  GATES_JSON="$(sonar_api "${SONAR_URL}/api/qualitygates/list" || true)"
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
      if sonar_api -X POST \
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
    if is_default_quality_gate_active "${DEFAULT_QUALITY_GATE}"; then
      log "Default quality gate already set to '${DEFAULT_QUALITY_GATE}'."
      return 0
    fi
    log "Error: unable to verify default quality gate '${DEFAULT_QUALITY_GATE}'."
    exit 5
  fi

  GATE_ID="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
data=json.loads(payload) if payload else {};\
target=sys.argv[2] if len(sys.argv)>2 else "";\
print(next((str(g.get("id","")) for g in data.get("qualitygates",[]) if g.get("name")==target),""))' "${GATES_JSON}" "${DEFAULT_QUALITY_GATE}" 2>/dev/null || true)"

  if sonar_api -X POST \
    "${SONAR_URL}/api/qualitygates/set_as_default" \
    --data-urlencode "name=${DEFAULT_QUALITY_GATE}" >/dev/null; then
    if is_default_quality_gate_active "${DEFAULT_QUALITY_GATE}"; then
      log "Default quality gate set to '${DEFAULT_QUALITY_GATE}'."
      return 0
    fi
    log "Error: failed to verify default quality gate '${DEFAULT_QUALITY_GATE}'."
    exit 5
  fi

  if [[ -n "${GATE_ID}" ]] && sonar_api -X POST \
    "${SONAR_URL}/api/qualitygates/set_as_default" \
    --data-urlencode "id=${GATE_ID}" >/dev/null; then
    if is_default_quality_gate_active "${DEFAULT_QUALITY_GATE}"; then
      log "Default quality gate set to '${DEFAULT_QUALITY_GATE}'."
      return 0
    fi
    log "Error: failed to verify default quality gate '${DEFAULT_QUALITY_GATE}'."
    exit 5
  fi

  log "Error: failed to set default quality gate '${DEFAULT_QUALITY_GATE}'."
  exit 5
}

write_runtime_env

export REPO_PREFIX SONAR_PORT SONAR_TOKEN

if [[ "${REUSE_RUNNING}" == "true" ]]; then
  if ! ensure_sonar_token; then
    echo "Unable to ensure SonarQube token." >&2
    exit 4
  fi
  write_runtime_env
  configure_default_quality_gate
  log "SonarQube is already running and quality gate policy is ensured."
  echo "SONAR_PORT=${SONAR_PORT}"
  echo "SONAR_URL=${SONAR_URL}"
  echo "SONAR_TOKEN=${SONAR_TOKEN}"
  exit 0
fi

if [[ "${REUSE_RUNNING}" == "true" ]]; then
  configure_default_quality_gate
  log "SonarQube is already running and quality gate policy is ensured."
  echo "SONAR_PORT=${SONAR_PORT}"
  echo "SONAR_URL=${SONAR_URL}"
  exit 0
fi

log "Starting SonarQube stack (${COMPOSE_PROJECT_NAME}) on port ${SONAR_PORT}..."
if ! docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" up -d; then
  echo "Failed to start SonarQube stack." >&2
  exit 4
fi

deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  if curl -sf "${SONAR_URL}/api/system/status" | grep -q '"status":"UP"'; then
    if ! ensure_sonar_token; then
      echo "Unable to ensure SonarQube token." >&2
      exit 4
    fi
    write_runtime_env
    configure_default_quality_gate
    log "SonarQube is UP."
    echo "SONAR_PORT=${SONAR_PORT}"
    echo "SONAR_URL=${SONAR_URL}"
    echo "SONAR_TOKEN=${SONAR_TOKEN}"
    exit 0
  fi
  sleep 2
done

echo "SonarQube did not become ready within ${TIMEOUT_SECONDS}s." >&2
docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" logs --tail=200 sonarqube || true
docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}" logs --tail=200 sonar_db || true
exit 3
