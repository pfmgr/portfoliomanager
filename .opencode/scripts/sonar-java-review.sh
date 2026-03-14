#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
START_SCRIPT="${ROOT_DIR}/.opencode/scripts/sonar-start.sh"
STOP_SCRIPT="${ROOT_DIR}/.opencode/scripts/sonar-stop.sh"
RUNTIME_ENV="${ROOT_DIR}/.opencode/docker/sonar/.runtime.env"

PROJECT_DIR=""
PROJECT_KEY=""
SRC_PATH=""
TESTS_PATH=""
KEEP_RUNNING=false
TOKEN_NAME=""
SONAR_TOKEN=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --project-key)
      PROJECT_KEY="$2"
      shift 2
      ;;
    --src)
      SRC_PATH="$2"
      shift 2
      ;;
    --tests)
      TESTS_PATH="$2"
      shift 2
      ;;
    --keep-running)
      KEEP_RUNNING=true
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 11
      ;;
  esac
done

log() {
  echo "$@"
}

if [[ ! -x "${START_SCRIPT}" ]]; then
  echo "Missing start script: ${START_SCRIPT}" >&2
  exit 12
fi

PYTHON_BIN=""
if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="python"
else
  echo "Python is required for SonarQube review." >&2
  exit 11
fi

if [[ -z "${PROJECT_DIR}" ]]; then
  if [[ -d "${ROOT_DIR}/services/app/backend" ]]; then
    PROJECT_DIR="${ROOT_DIR}/services/app/backend"
  else
    PROJECT_DIR="${ROOT_DIR}"
  fi
fi

if [[ -z "${PROJECT_KEY}" ]]; then
  REPO_NAME="$(basename "${ROOT_DIR}")"
  PROJECT_KEY="$(printf '%s' "${REPO_NAME}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
  if [[ -z "${PROJECT_KEY}" ]]; then
    PROJECT_KEY="repo"
  fi
fi

if [[ -z "${SRC_PATH}" ]]; then
  if [[ -d "${PROJECT_DIR}/src/main/java" ]]; then
    SRC_PATH="src/main/java"
  elif [[ -d "${PROJECT_DIR}/src" ]]; then
    SRC_PATH="src"
  else
    SRC_PATH="."
  fi
fi

if [[ -z "${TESTS_PATH}" ]]; then
  if [[ -d "${PROJECT_DIR}/src/test/java" ]]; then
    TESTS_PATH="src/test/java"
  else
    TESTS_PATH=""
  fi
fi

if [[ ! -d "${PROJECT_DIR}" ]]; then
  echo "Project directory not found: ${PROJECT_DIR}" >&2
  exit 11
fi

if [[ "${PROJECT_DIR}" != /* ]]; then
  PROJECT_DIR="$(cd "${ROOT_DIR}" && cd "${PROJECT_DIR}" && pwd)"
fi

cleanup() {
  if [[ -n "${SONAR_TOKEN}" && -n "${TOKEN_NAME}" && -n "${SONAR_URL:-}" ]]; then
    curl -s -u "${SONAR_LOGIN}:${SONAR_PASSWORD}" -X POST \
      "${SONAR_URL}/api/user_tokens/revoke" \
      --data-urlencode "name=${TOKEN_NAME}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_RUNNING}" != "true" ]]; then
    "${STOP_SCRIPT}" --quiet || true
  fi
}

trap cleanup EXIT

log "Starting SonarQube for review..."
START_OUTPUT="$(${START_SCRIPT} --quiet || true)"
if [[ -z "${START_OUTPUT}" ]]; then
  echo "SonarQube start failed." >&2
  exit 12
fi

SONAR_URL="$(printf '%s' "${START_OUTPUT}" | grep '^SONAR_URL=' | cut -d= -f2-)"
if [[ -z "${SONAR_URL}" ]]; then
  if [[ -f "${RUNTIME_ENV}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${RUNTIME_ENV}"
    set +a
    SONAR_URL="${SONAR_URL:-}"
  fi
fi

if [[ -z "${SONAR_URL}" ]]; then
  echo "Unable to determine SonarQube URL." >&2
  exit 12
fi

SONAR_LOGIN="admin"
SONAR_PASSWORD="admin"

TOKEN_NAME="review_$(date +%s%N)_$RANDOM"
TOKEN_JSON="$(curl -s -u "${SONAR_LOGIN}:${SONAR_PASSWORD}" -X POST "${SONAR_URL}/api/user_tokens/generate" --data-urlencode "name=${TOKEN_NAME}")"
SONAR_TOKEN="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
print(json.loads(payload).get("token","")) if payload else print("")' "${TOKEN_JSON}" 2>/dev/null || true)"

if [[ -z "${SONAR_TOKEN}" ]]; then
  echo "Unable to generate SonarQube token using admin/admin." >&2
  exit 11
fi

log "Running SonarQube scan on ${PROJECT_DIR}..."

BINARIES_PATH=""
if [[ -d "${PROJECT_DIR}/build/classes/java/main" ]]; then
  BINARIES_PATH="build/classes/java/main"
elif [[ -d "${PROJECT_DIR}/target/classes" ]]; then
  BINARIES_PATH="target/classes"
fi

SCANNER_ARGS=(
  "-Dsonar.host.url=${SONAR_URL}"
  "-Dsonar.token=${SONAR_TOKEN}"
  "-Dsonar.projectKey=${PROJECT_KEY}"
  "-Dsonar.sources=${SRC_PATH}"
)

if [[ -n "${TESTS_PATH}" ]]; then
  SCANNER_ARGS+=("-Dsonar.tests=${TESTS_PATH}")
fi

if [[ -n "${BINARIES_PATH}" ]]; then
  SCANNER_ARGS+=("-Dsonar.java.binaries=${BINARIES_PATH}")
fi

if ! docker run --rm \
  --network host \
  -v "${PROJECT_DIR}":/workspace \
  -w /workspace \
  sonarsource/sonar-scanner-cli:5.0.1 \
  "${SCANNER_ARGS[@]}"; then
  echo "SonarQube scan failed." >&2
  exit 11
fi

log "Fetching quality gate status..."
STATUS_JSON="$(curl -s -u "${SONAR_LOGIN}:${SONAR_PASSWORD}" "${SONAR_URL}/api/qualitygates/project_status?projectKey=${PROJECT_KEY}")"
GATE_STATUS="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
print(json.loads(payload).get("projectStatus",{}).get("status","")) if payload else print("")' "${STATUS_JSON}" 2>/dev/null || true)"

if [[ -z "${GATE_STATUS}" ]]; then
  echo "Unable to read SonarQube quality gate status." >&2
  exit 11
fi

log "Quality Gate: ${GATE_STATUS}"

ISSUES_JSON="$(curl -s -u "${SONAR_LOGIN}:${SONAR_PASSWORD}" "${SONAR_URL}/api/issues/search?projectKeys=${PROJECT_KEY}&resolved=false&ps=10")"
ISSUES_OUTPUT="$(PAYLOAD="${ISSUES_JSON}" ${PYTHON_BIN} - <<'PY' 2>/dev/null || printf 'No issues data available.'
import json
import os

payload = os.environ.get("PAYLOAD", "")
data = json.loads(payload) if payload else {}
issues = data.get("issues", [])

if not issues:
    print("No open issues reported.")
else:
    for issue in issues:
        severity = issue.get("severity", "")
        key = issue.get("key", "")
        component = issue.get("component", "")
        message = issue.get("message", "")
        print(f"- {severity} {key} {component}: {message}")
PY
)"

log "Top Issues:"
log "${ISSUES_OUTPUT}"

if [[ "${GATE_STATUS}" == "OK" ]]; then
  exit 0
fi

exit 10
