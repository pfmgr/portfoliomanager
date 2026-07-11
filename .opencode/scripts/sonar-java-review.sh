#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
START_SCRIPT="${ROOT_DIR}/.opencode/scripts/sonar-start.sh"
STOP_SCRIPT="${ROOT_DIR}/.opencode/scripts/sonar-stop.sh"
RUNTIME_DIR="${ROOT_DIR}/.opencode/docker/sonar/runtime"
ANALYSIS_ENV="${RUNTIME_DIR}/analysis.env"
READ_ENV="${RUNTIME_DIR}/read.env"

PROJECT_DIR=""
PROJECT_KEY=""
SRC_PATH=""
TESTS_PATH=""
KEEP_RUNNING=false
ANALYSIS_TOKEN=""
READ_TOKEN=""
SONAR_SCANNER_IMAGE="${SONAR_SCANNER_IMAGE:-sonarsource/sonar-scanner-cli@sha256:02372948eaeeb10dfbe0cfd4174d44b8e405d0aeae431532b2bdb21d0347bf23}"
EXPECTED_SCANNER_IMAGE="sonarsource/sonar-scanner-cli@sha256:02372948eaeeb10dfbe0cfd4174d44b8e405d0aeae431532b2bdb21d0347bf23"
SCANNER_MEMORY="${SONAR_SCANNER_MEMORY:-768m}"
SCANNER_CPUS="${SONAR_SCANNER_CPUS:-1.0}"
SCANNER_PIDS_LIMIT="${SONAR_SCANNER_PIDS_LIMIT:-256}"
SCANNER_NOFILE="${SONAR_SCANNER_NOFILE:-1024:2048}"

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

load_runtime_value() {
  local runtime_file="$1" wanted="$2"
  [[ -f "${runtime_file}" ]] || return 0
  "${PYTHON_BIN}" - "${RUNTIME_DIR}" "${runtime_file}" "${wanted}" <<'PY' || { echo "Unsafe Sonar runtime configuration." >&2; exit 12; }
import os, stat, sys
directory, path, wanted = sys.argv[1:]
for candidate, expected, is_dir in ((directory, 0o700, True), (path, 0o600, False)):
    s = os.lstat(candidate)
    if stat.S_ISLNK(s.st_mode) or s.st_uid != os.getuid() or stat.S_IMODE(s.st_mode) != expected or (is_dir and not stat.S_ISDIR(s.st_mode)) or (not is_dir and not stat.S_ISREG(s.st_mode)):
        raise SystemExit(1)
allowed = {"SONAR_URL", "SONAR_PROJECT_KEY", "SONAR_ANALYSIS_TOKEN", "SONAR_READ_TOKEN"}
if wanted not in allowed:
    raise SystemExit(2)
values = {}
for line in open(path, encoding="utf-8"):
    key, separator, value = line.rstrip("\n").partition("=")
    if not separator or key not in allowed or not value or "\x00" in value or key in values:
        raise SystemExit("invalid runtime value")
    values[key] = value
print(values.get(wanted, ""))
PY
}

resolve_project_dir() {
  "${PYTHON_BIN}" - "$ROOT_DIR" "$1" <<'PY'
import os
import sys

root = os.path.realpath(sys.argv[1])
candidate = sys.argv[2]
resolved = os.path.realpath(candidate if os.path.isabs(candidate) else os.path.join(root, candidate))

if not os.path.isdir(resolved):
    print(f"Project directory not found: {candidate}", file=sys.stderr)
    sys.exit(11)

root_prefix = root if root.endswith(os.sep) else root + os.sep
if resolved != root and not resolved.startswith(root_prefix):
    print(f"Project directory must be within ROOT_DIR: {candidate}", file=sys.stderr)
    sys.exit(11)

print(resolved)
PY
}

if [[ ! -x "${START_SCRIPT}" ]]; then
  echo "Missing start script: ${START_SCRIPT}" >&2
  exit 12
fi

for forbidden in SONAR_TOKEN SONAR_ADMIN_TOKEN SONAR_ANALYSIS_TOKEN SONAR_READ_TOKEN; do
  if [[ -v "$forbidden" ]]; then
    echo "Externally supplied ${forbidden} is not accepted as a Sonar runtime token; preflight blocked." >&2
    exit 12
  fi
done

PYTHON_BIN=""
if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="python"
else
  echo "Python is required for SonarQube review." >&2
  exit 11
fi

# Resource limits may be tuned for a local machine, but never expanded into a
# host-exhausting scanner.  Invalid values (including values below the useful
# floor) and values above these fixed conservative ceilings fall back to the
# reviewed defaults instead of being passed to Docker.
clamp_scanner_resources() {
  local normalized
  normalized="$(${PYTHON_BIN} - "$SCANNER_MEMORY" "$SCANNER_CPUS" "$SCANNER_PIDS_LIMIT" "$SCANNER_NOFILE" <<'PY'
from decimal import Decimal, InvalidOperation
import re
import sys

defaults = ("768m", "1.0", "256", "1024:2048")
memory, cpus, pids, nofile = sys.argv[1:]

def bounded_memory(value):
    match = re.fullmatch(r"([1-9][0-9]*)([kKmMgG])", value)
    if not match:
        return None
    number, unit = match.groups()
    bytes_value = int(number) * {"k": 1024, "m": 1024**2, "g": 1024**3}[unit.lower()]
    return 256 * 1024**2 <= bytes_value <= 1024 * 1024**2

def bounded_cpus(value):
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        return False
    return parsed.is_finite() and Decimal("0.25") <= parsed <= Decimal("2.0")

def bounded_pids(value):
    return value.isdecimal() and 64 <= int(value) <= 512

def bounded_nofile(value):
    match = re.fullmatch(r"([1-9][0-9]*):([1-9][0-9]*)", value)
    if not match:
        return False
    soft, hard = map(int, match.groups())
    return 512 <= soft <= hard <= 4096

checks = (bounded_memory(memory), bounded_cpus(cpus), bounded_pids(pids), bounded_nofile(nofile))
for supplied, default, valid in zip((memory, cpus, pids, nofile), defaults, checks):
    if not valid:
        print(f"Scanner resource value {supplied!r} is outside fixed safe bounds; using {default}.", file=sys.stderr)
        print(default)
    else:
        print(supplied)
PY
)" || { echo "Unable to validate scanner resource limits." >&2; exit 12; }
  mapfile -t _scanner_resources <<<"${normalized}"
  ((${#_scanner_resources[@]} == 4)) || { echo "Unable to normalize scanner resource limits." >&2; exit 12; }
  SCANNER_MEMORY="${_scanner_resources[0]}"
  SCANNER_CPUS="${_scanner_resources[1]}"
  SCANNER_PIDS_LIMIT="${_scanner_resources[2]}"
  SCANNER_NOFILE="${_scanner_resources[3]}"
}

clamp_scanner_resources

if [[ -z "${PROJECT_DIR}" ]]; then
  if [[ -d "${ROOT_DIR}/services/app/backend" ]]; then
    PROJECT_DIR="${ROOT_DIR}/services/app/backend"
  else
    PROJECT_DIR="${ROOT_DIR}"
  fi
fi

PROJECT_DIR="$(resolve_project_dir "${PROJECT_DIR}")"

if [[ -z "${PROJECT_KEY}" ]]; then
  REPO_NAME="$(basename "${ROOT_DIR}")"
  PROJECT_KEY="$(printf '%s' "${REPO_NAME}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
  if [[ -z "${PROJECT_KEY}" ]]; then
    PROJECT_KEY="repo"
  fi
fi

REPO_PREFIX="$(basename "${ROOT_DIR}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
[[ -n "${REPO_PREFIX}" ]] || REPO_PREFIX="repo"
SCANNER_SONAR_URL="http://sonarqube:9000"

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

cleanup() {
  if [[ "${KEEP_RUNNING}" != "true" ]]; then
    "${STOP_SCRIPT}" --quiet || true
  fi
}

trap cleanup EXIT

log "Starting SonarQube for review..."
if ! START_OUTPUT="$(${START_SCRIPT} --quiet --project-key "${PROJECT_KEY}" 2>&1)"; then
  if [[ -n "${START_OUTPUT}" ]]; then
    printf '%s\n' "${START_OUTPUT}" >&2
  fi
  echo "SonarQube start failed." >&2
  exit 12
fi

SONAR_URL="$(printf '%s' "${START_OUTPUT}" | "${PYTHON_BIN}" -c 'import sys; print(next((line[10:] for line in sys.stdin.read().splitlines() if line.startswith("SONAR_URL=")), ""))')"
RUNTIME_PROJECT_KEY="$(load_runtime_value "${READ_ENV}" SONAR_PROJECT_KEY)"
[[ -z "${RUNTIME_PROJECT_KEY}" || "${RUNTIME_PROJECT_KEY}" == "${PROJECT_KEY}" ]] || { echo "Sonar runtime project mismatch." >&2; exit 12; }
RUNTIME_URL="$(load_runtime_value "${READ_ENV}" SONAR_URL)"
[[ -z "${RUNTIME_URL}" || "${RUNTIME_URL}" == "${SONAR_URL}" ]] || { echo "Sonar runtime URL mismatch." >&2; exit 12; }
ANALYSIS_TOKEN="$(load_runtime_value "${ANALYSIS_ENV}" SONAR_ANALYSIS_TOKEN)"
READ_TOKEN="$(load_runtime_value "${READ_ENV}" SONAR_READ_TOKEN)"

if [[ -z "${SONAR_URL}" ]]; then
  echo "Unable to determine SonarQube URL." >&2
  exit 12
fi

if [[ -z "${ANALYSIS_TOKEN}" || -z "${READ_TOKEN}" ]]; then
  echo "Project-scoped Sonar analysis and read tokens are required; admin tokens are never accepted." >&2
  exit 11
fi

sonar_api() {
  curl -sf -H "Authorization: Bearer ${READ_TOKEN}" "$@"
}

ensure_project_quality_gate() {
  local required_gate="${PROJECT_KEY} Preflight"
  local assigned
  assigned="$(sonar_api -G "${SONAR_URL}/api/qualitygates/get_by_project" --data-urlencode "project=${PROJECT_KEY}" 2>/dev/null | "${PYTHON_BIN}" -c 'import json,sys; print(json.load(sys.stdin).get("qualityGate", {}).get("name", ""))' 2>/dev/null || true)"
  if [[ "${assigned}" != "${required_gate}" ]]; then
    echo "Required SonarQube quality gate is not bound to this project." >&2
    return 1
  fi

  # This is the pre-test gate. It must remain strictly about bug/security/
  # reliability findings; coverage is evaluated by the later test gate.
  local gate_json
  gate_json="$(sonar_api -G "${SONAR_URL}/api/qualitygates/show" --data-urlencode "name=${required_gate}" 2>/dev/null || true)"
  GATE_JSON="${gate_json}" "${PYTHON_BIN}" - <<'PY' || {
import json, os, sys
try:
    conditions = json.loads(os.environ["GATE_JSON"]).get("conditions", [])
except (KeyError, json.JSONDecodeError):
    raise SystemExit(1)
required = {("new_bugs", "GT", "0"), ("new_vulnerabilities", "GT", "0"), ("new_reliability_rating", "GT", "1"), ("new_security_rating", "GT", "1")}
actual = {(str(condition.get("metric")), str(condition.get("op")), str(condition.get("error"))) for condition in conditions}
if actual != required:
    raise SystemExit(1)
PY
    echo "Pre-test quality gate must contain bug/security/reliability policy and no coverage condition." >&2
    return 1
  }
}

compile_preflight() {
  log "Preflight compilation (test-free): producing Java classes required by SonarQube; this does not run tests."
  if [[ -x "${PROJECT_DIR}/gradlew" ]]; then
    (cd "${PROJECT_DIR}" && env -u SONAR_BOOTSTRAP_ADMIN_TOKEN -u SONAR_BOOTSTRAP_PASSWORD -u SONAR_ADMIN_TOKEN -u SONAR_ADMIN_PASSWORD -u SONAR_ADMIN_USER ./gradlew classes -x test)
  elif [[ -f "${PROJECT_DIR}/pom.xml" ]] && command -v mvn >/dev/null 2>&1; then
    (cd "${PROJECT_DIR}" && env -u SONAR_BOOTSTRAP_ADMIN_TOKEN -u SONAR_BOOTSTRAP_PASSWORD -u SONAR_ADMIN_TOKEN -u SONAR_ADMIN_PASSWORD -u SONAR_ADMIN_USER mvn --batch-mode -DskipTests compile)
  else
    echo "No supported Gradle wrapper or Maven build is available for test-free preflight compilation." >&2
    return 1
  fi
}

wait_for_quality_gate_status() {
  local timeout_seconds="${1:-120}"
  local deadline
  local status_json
  local gate_status

  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    status_json="$(sonar_api "${SONAR_URL}/api/qualitygates/project_status?projectKey=${PROJECT_KEY}" 2>/dev/null || true)"
    gate_status="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";
print(json.loads(payload).get("projectStatus",{}).get("status","")) if payload else print("")' "${status_json}" 2>/dev/null || true)"

    if [[ -n "${gate_status}" && "${gate_status}" != "NONE" ]]; then
      printf '%s\n' "${status_json}"
      return 0
    fi

    sleep 2
  done

  return 1
}

log "Running SonarQube scan on ${PROJECT_DIR}..."

if [[ "${SONAR_SCANNER_IMAGE}" != "${EXPECTED_SCANNER_IMAGE}" ]]; then
  echo "Setup blocker: scanner image is not the approved pinned digest; preflight is blocked." >&2
  exit 12
fi
if ! scanner_digests="$(docker image inspect --format '{{join .RepoDigests "\n"}}' "${EXPECTED_SCANNER_IMAGE}" 2>/dev/null)" || ! grep -Fxq "${EXPECTED_SCANNER_IMAGE}" <<<"${scanner_digests}"; then
  echo "blocked: approved scanner image is unavailable locally; automatic pulls are disabled." >&2
  exit 12
fi

if ! ensure_project_quality_gate; then
  echo "SonarQube preflight blocked because the required quality-gate policy is not bound." >&2
  exit 12
fi

compile_preflight || { echo "Preflight compilation failed; SonarQube scan was not started." >&2; exit 11; }

BINARIES_PATH=""
if [[ -d "${PROJECT_DIR}/build/classes/java/main" ]]; then
  BINARIES_PATH="build/classes/java/main"
elif [[ -d "${PROJECT_DIR}/target/classes" ]]; then
  BINARIES_PATH="target/classes"
fi

SCANNER_ARGS=(
  "-Dsonar.host.url=${SCANNER_SONAR_URL}"
  "-Dsonar.projectKey=${PROJECT_KEY}"
  "-Dsonar.sources=${SRC_PATH}"
  "-Dsonar.working.directory=/tmp/sonar-scanner-work-${PROJECT_KEY}"
  "-Dsonar.exclusions=.opencode/docker/sonar/runtime/**,.opencode/docker/sonar/*.env,**/.runtime.env"
)

if [[ -n "${TESTS_PATH}" ]]; then
  SCANNER_ARGS+=("-Dsonar.tests=${TESTS_PATH}")
fi

if [[ -n "${BINARIES_PATH}" ]]; then
  SCANNER_ARGS+=("-Dsonar.java.binaries=${BINARIES_PATH}")
fi

if ! docker run --rm \
  --network "${REPO_PREFIX}_sonar_scanner_network" \
  --user 1000:1000 \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --memory "$SCANNER_MEMORY" \
  --cpus "$SCANNER_CPUS" \
  --pids-limit "$SCANNER_PIDS_LIMIT" \
  --ulimit "nofile=$SCANNER_NOFILE" \
  --tmpfs /tmp:rw,noexec,nosuid,size=256m \
  --tmpfs /opt/sonar-scanner/.sonar:rw,noexec,nosuid,size=256m \
  -e "SONAR_TOKEN=${ANALYSIS_TOKEN}" \
  -v "${PROJECT_DIR}":/workspace:ro \
  -w /workspace \
  "${SONAR_SCANNER_IMAGE}" \
  "${SCANNER_ARGS[@]}"; then
  echo "SonarQube scan failed." >&2
  exit 11
fi

log "Fetching quality gate status..."
STATUS_JSON="$(wait_for_quality_gate_status 180 || true)"
GATE_STATUS="$(${PYTHON_BIN} -c 'import json,sys; payload=sys.argv[1] if len(sys.argv)>1 else "";\
print(json.loads(payload).get("projectStatus",{}).get("status","")) if payload else print("")' "${STATUS_JSON}" 2>/dev/null || true)"

if [[ -z "${GATE_STATUS}" ]]; then
  echo "Unable to read SonarQube quality gate status." >&2
  exit 11
fi

log "Quality Gate: ${GATE_STATUS}"

ISSUES_JSON="$(sonar_api "${SONAR_URL}/api/issues/search?projectKeys=${PROJECT_KEY}&resolved=false&ps=10")"
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
