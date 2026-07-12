#!/usr/bin/env bash
set -euo pipefail

# Starts SonarQube without a host port, performs first-use bootstrap over the
# container's loopback interface, then publishes a loopback-only host port.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.yaml"
EXPOSE_COMPOSE_FILE="${ROOT_DIR}/.opencode/docker/sonar/docker-compose.expose.yaml"
RUNTIME_DIR="${ROOT_DIR}/.opencode/docker/sonar/runtime"
ANALYSIS_ENV="${RUNTIME_DIR}/analysis.env"
READ_ENV="${RUNTIME_DIR}/read.env"
BOOTSTRAP_ENV="${RUNTIME_DIR}/bootstrap.env"
QUIET=false PORT_OVERRIDE="" TIMEOUT_SECONDS=600 PROJECT_KEY=""

readonly EXIT_ARGUMENT=41
readonly EXIT_CONFIG=42
readonly EXIT_IMAGE=43
readonly EXIT_START=44
readonly EXIT_READINESS=45
readonly EXIT_AUTH=46
readonly EXIT_PROJECT_POLICY=47
readonly EXIT_PERMISSION=48
readonly EXIT_TOKEN=49

fail() {
  local code="$1"
  shift
  printf '%s\n' "$*" >&2
  exit "$code"
}

validate_positive_integer() {
  [[ "$1" =~ ^[0-9]+$ ]] && (( $1 > 0 ))
}

compose() {
  timeout 120s docker compose -f "$COMPOSE_FILE" -p "$COMPOSE_PROJECT_NAME" "$@"
}

compose_exposed() {
  timeout 120s docker compose -f "$COMPOSE_FILE" -f "$EXPOSE_COMPOSE_FILE" -p "$COMPOSE_PROJECT_NAME" "$@"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-key) PROJECT_KEY="${2:?missing project key}"; shift 2 ;;
    --port) PORT_OVERRIDE="${2:?missing port}"; shift 2 ;;
    --timeout) TIMEOUT_SECONDS="${2:?missing timeout}"; shift 2 ;;
    --quiet) QUIET=true; shift ;;
    *) fail "$EXIT_ARGUMENT" "argument: unknown argument: $1" ;;
  esac
done
log() { [[ "$QUIET" == true ]] || printf '%s\n' "$*" >&2; }
command -v python3 >/dev/null || fail "$EXIT_CONFIG" "config: Python 3 is required."
[[ -f "$COMPOSE_FILE" && -f "$EXPOSE_COMPOSE_FILE" ]] || fail "$EXIT_CONFIG" "config: Sonar Compose files are missing."
validate_positive_integer "$TIMEOUT_SECONDS" || fail "$EXIT_ARGUMENT" "argument: --timeout must be a positive integer."
PROJECT_KEY="${PROJECT_KEY:-$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')}"
[[ "$PROJECT_KEY" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail "$EXIT_ARGUMENT" "argument: invalid Sonar project key."
REPO_PREFIX="$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')"
REPO_PREFIX="${REPO_PREFIX:-repo}"
COMPOSE_PROJECT_NAME="${REPO_PREFIX}_sonar"

safe_value() {
  python3 - "$RUNTIME_DIR" "$1" "$2" <<'PY'
import os, stat, sys
directory, path, wanted = sys.argv[1:]
for candidate, kind, mode in ((directory, stat.S_ISDIR, 0o700), (path, stat.S_ISREG, 0o600)):
    try: info = os.lstat(candidate)
    except FileNotFoundError: raise SystemExit(0)
    if stat.S_ISLNK(info.st_mode) or not kind(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != mode:
        raise SystemExit("unsafe Sonar runtime path")
allowed = {"SONAR_URL", "SONAR_PROJECT_KEY", "SONAR_ANALYSIS_TOKEN", "SONAR_READ_TOKEN", "SONAR_BOOTSTRAP_PASSWORD"}
if wanted not in allowed: raise SystemExit(2)
values = {}
with open(path, encoding="utf-8") as source:
    for line in source:
        key, separator, value = line.rstrip("\n").partition("=")
        if not separator or key not in allowed or not value or "\x00" in value or key in values:
            raise SystemExit("invalid Sonar runtime data")
        values[key] = value
print(values.get(wanted, ""))
PY
}
prepare_runtime_dir() {
  if [[ -e "$RUNTIME_DIR" ]]; then
    python3 - "$RUNTIME_DIR" <<'PY'
import os, stat, sys
s=os.lstat(sys.argv[1])
if stat.S_ISLNK(s.st_mode) or not stat.S_ISDIR(s.st_mode) or s.st_uid != os.getuid() or stat.S_IMODE(s.st_mode) != 0o700: raise SystemExit(1)
PY
  else
    (umask 077 && mkdir "$RUNTIME_DIR")
  fi
}
write_runtime_file() {
  local path="$1" temporary
  prepare_runtime_dir || fail "$EXIT_CONFIG" "config: unsafe Sonar runtime directory."
  [[ ! -L "$path" ]] || fail "$EXIT_CONFIG" "config: unsafe Sonar runtime file."
  temporary="$(mktemp "$RUNTIME_DIR/.runtime.XXXXXX")"
  chmod 600 "$temporary"
  # stdin keeps line endings literal and the rename makes the update atomic.
  cat >"$temporary"
  mv -f "$temporary" "$path"
}
write_bootstrap_file() { write_runtime_file "$BOOTSTRAP_ENV" <<EOF
SONAR_BOOTSTRAP_PASSWORD=$1
EOF
}
write_analysis_file() { write_runtime_file "$ANALYSIS_ENV" <<EOF
SONAR_URL=$SONAR_URL
SONAR_PROJECT_KEY=$PROJECT_KEY
SONAR_ANALYSIS_TOKEN=$ANALYSIS_TOKEN
EOF
}
write_read_file() { write_runtime_file "$READ_ENV" <<EOF
SONAR_URL=$SONAR_URL
SONAR_PROJECT_KEY=$PROJECT_KEY
SONAR_READ_TOKEN=$READ_TOKEN
EOF
}
random_secret() {
  python3 - <<'PY'
import secrets, string
alphabet = string.ascii_letters + string.digits + "!@#$%^&*()-_=+[]{}:,.?"
chars = [
    secrets.choice(string.ascii_uppercase),
    secrets.choice(string.ascii_lowercase),
    secrets.choice(string.digits),
    secrets.choice("!@#$%^&*()-_=+[]{}:,.?"),
]
chars.extend(secrets.choice(alphabet) for _ in range(44))
secrets.SystemRandom().shuffle(chars)
print(''.join(chars))
PY
}
port_free() { python3 - "$1" <<'PY'
import socket, sys
s=socket.socket()
try: s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError: raise SystemExit(1)
finally: s.close()
PY
}

sonar_http_request() {
  local step="$1" endpoint_class="$2" exit_code="$3" mode="$4"
  shift 4
  local response status body
  # curl executes in the SonarQube container. Capture its output instead of
  # passing it a host-local temporary path, which the container cannot write.
  if ! response="$(timeout 45s docker exec -i "$CONTAINER_ID" curl -sS --connect-timeout 5 --max-time 30 -w $'\n%{http_code}' "$@" 2>/dev/null)"; then
    fail "$exit_code" "${step} failed: endpoint=${endpoint_class} status=transport"
  fi
  status="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ ! "$status" =~ ^[0-9]{3}$ ]]; then
    fail "$exit_code" "${step} failed: endpoint=${endpoint_class} status=invalid"
  fi
  case "$mode" in
    status)
      printf '%s\n' "$status"
      ;;
    body)
      if (( status < 200 || status >= 300 )); then
        fail "$exit_code" "${step} failed: endpoint=${endpoint_class} status=${status}"
      fi
      printf '%s' "$body"
      ;;
    *)
      fail "$EXIT_CONFIG" "config: invalid sonar_http_request mode"
      ;;
  esac
}

sonar_status_request() {
  sonar_http_request "$1" "$2" "$3" status "${@:4}"
}

sonar_body_request() {
  sonar_http_request "$1" "$2" "$3" body "${@:4}"
}

sonar_permission_users_request() {
  api_request "$1" "$2" permissions body -G http://127.0.0.1:9000/api/permissions/users "${@:3}"
}

sonar_permission_groups_request() {
  api_request "$1" "$2" permissions body -G http://127.0.0.1:9000/api/permissions/groups "${@:3}"
}

api_request() {
  local exit_code="$1" step="$2" endpoint_class="$3" mode="$4"
  shift 4
  sonar_http_request "$step" "$endpoint_class" "$exit_code" "$mode" "$@"
}

# Runtime credentials are generated and retained only in protected local files.
for forbidden in SONAR_TOKEN SONAR_ADMIN_TOKEN SONAR_ANALYSIS_TOKEN SONAR_READ_TOKEN; do
  if [[ -v "$forbidden" ]]; then
    fail "$EXIT_ARGUMENT" "argument: externally supplied ${forbidden} is not accepted as a Sonar runtime token."
  fi
done
ANALYSIS_TOKEN=""
READ_TOKEN=""
if [[ -f "$ANALYSIS_ENV" ]]; then ANALYSIS_TOKEN="$(safe_value "$ANALYSIS_ENV" SONAR_ANALYSIS_TOKEN)" || fail "$EXIT_CONFIG" "config: unsafe analysis runtime file."; fi
if [[ -f "$READ_ENV" ]]; then READ_TOKEN="$(safe_value "$READ_ENV" SONAR_READ_TOKEN)" || fail "$EXIT_CONFIG" "config: unsafe read runtime file."; fi
SONAR_PORT="$PORT_OVERRIDE"
if [[ -z "$SONAR_PORT" && -f "$READ_ENV" ]]; then SONAR_URL="$(safe_value "$READ_ENV" SONAR_URL)" || fail "$EXIT_CONFIG" "config: unsafe read runtime file."; SONAR_PORT="${SONAR_URL##*:}"; fi
if [[ -z "$SONAR_PORT" ]]; then for port in $(seq 9000 9100); do port_free "$port" && { SONAR_PORT="$port"; break; }; done; fi
[[ "$SONAR_PORT" =~ ^[0-9]+$ ]] && (( SONAR_PORT > 0 && SONAR_PORT < 65536 )) || fail "$EXIT_ARGUMENT" "argument: no valid free SonarQube port found."
SONAR_URL="http://127.0.0.1:${SONAR_PORT}"
export REPO_PREFIX SONAR_PORT
compose config >/dev/null || fail "$EXIT_CONFIG" "config: Sonar Compose configuration is invalid."

require_local_image() {
  local approved="$1" digests
  if ! digests="$(timeout 30s docker image inspect --format '{{join .RepoDigests "\n"}}' "$approved" 2>/dev/null)" || ! grep -Fxq "$approved" <<<"$digests"; then
    fail "$EXIT_IMAGE" "image: approved image is unavailable locally: ${approved}."
  fi
}
require_local_image "postgres@sha256:056b54f00419b49289227ab12d09df508543883f407fe9935a2cec430ef8aa8d"
require_local_image "sonarqube@sha256:24d75d7e0021f2d0f94e4d761b734088b1afc00395d161a7035d61df5a812f5b"

# No host port exists during this phase.
compose up -d >/dev/null || fail "$EXIT_START" "start: docker compose up failed."
CONTAINER_ID="$(compose ps -q sonarqube)"
[[ -n "$CONTAINER_ID" ]] || fail "$EXIT_START" "start: SonarQube container was not created."
deadline=$((SECONDS + TIMEOUT_SECONDS))
last_status=""
until [[ "$last_status" == 200 ]]; do
  if (( SECONDS >= deadline )); then
    fail "$EXIT_READINESS" "readiness: SonarQube did not become ready; endpoint=system status=${last_status:-timeout}."
  fi
  readiness_response=""
  if ! readiness_response="$(timeout 30s docker exec -i "$CONTAINER_ID" curl -sS --connect-timeout 5 --max-time 15 -w $'\n%{http_code}' http://127.0.0.1:9000/api/system/status 2>/dev/null)"; then
    last_status="transport"
  else
    last_status="${readiness_response##*$'\n'}"
  fi
  if [[ "$last_status" == 200 ]]; then
    system_status="$(printf '%s' "${readiness_response%$'\n'*}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))' 2>/dev/null || printf 'invalid-json')"
    if [[ "$system_status" == UP ]]; then
      break
    fi
    last_status="${system_status}"
  fi
  sleep 2
done

token_login() {
  local token="$1" response status body
  if ! response="$(timeout 30s docker exec -i "$CONTAINER_ID" curl -sS --connect-timeout 5 --max-time 15 -u "${token}:" -w $'\n%{http_code}' http://127.0.0.1:9000/api/authentication/validate 2>/dev/null)"; then
    printf '%s\n' ""
    return 0
  fi
  status="${response##*$'\n'}"
  if [[ "$status" != 200 ]]; then
    printf '%s\n' ""
    return 0
  fi
  body="${response%$'\n'*}"
  python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get("login", "") if data.get("valid") else "")' <<<"$body"
}

sonar_user_record() {
  local account="$1"
  admin_api_body "$EXIT_AUTH" "auth:lookup-${account}-user" users -G http://127.0.0.1:9000/api/users/search --data-urlencode "q=${account}" --data-urlencode ps=500 \
    | python3 -c 'import json,sys; account=sys.argv[1]; data=json.load(sys.stdin); user=next((u for u in data.get("users", []) if u.get("login") == account), None); print(json.dumps(user) if user else "", end="")' "$account"
}

sonar_user_is_local() {
  local user_json="$1"
  USER_JSON="$user_json" python3 - <<'PY'
import json, os
user = json.loads(os.environ['USER_JSON'])
print('true' if not user.get('externalIdentity') and not user.get('externalProvider') else 'false')
PY
}

bootstrap_service_account() {
  local account="$1" display_name="$2" password_var="$3"
  local user_json account_exists is_local
  user_json="$(sonar_user_record "$account")"
  account_exists=false
  [[ -n "$user_json" ]] && account_exists=true

  if [[ "$account_exists" == true ]]; then
    is_local="$(sonar_user_is_local "$user_json")"
    if [[ "$is_local" != true ]]; then
      log "auth:${display_name}-account exists but is externally managed; retaining existing account and tokens."
      return 1
    fi
    admin_api_body "$EXIT_AUTH" "auth:set-${display_name}-password" users -X POST http://127.0.0.1:9000/api/users/change_password --data-urlencode "login=${account}" --data-urlencode "password=${!password_var}" >/dev/null
  else
    admin_api_status "$EXIT_AUTH" "auth:create-${display_name}-user" users -X POST http://127.0.0.1:9000/api/users/create --data-urlencode "login=${account}" --data-urlencode "name=${account}" --data-urlencode "password=${!password_var}" >/dev/null
  fi
  return 0
}
SCANNER_USER="opencode_scan_${PROJECT_KEY}"
READER_USER="opencode_read_${PROJECT_KEY}"

if [[ -f "$BOOTSTRAP_ENV" ]]; then
    ADMIN_PASSWORD="$(safe_value "$BOOTSTRAP_ENV" SONAR_BOOTSTRAP_PASSWORD)" || fail "$EXIT_CONFIG" "config: unsafe Sonar bootstrap runtime file."
    [[ -n "$ADMIN_PASSWORD" ]] || fail "$EXIT_CONFIG" "config: missing persistent Sonar bootstrap data."
else
    ADMIN_PASSWORD="$(random_secret)"
    api_request "$EXIT_AUTH" "auth:bootstrap-admin-password" users body -u 'admin:admin' -X POST http://127.0.0.1:9000/api/users/change_password --data-urlencode login=admin --data-urlencode previousPassword=admin --data-urlencode "password=${ADMIN_PASSWORD}" >/dev/null
    write_bootstrap_file "$ADMIN_PASSWORD"
fi

admin_api_status() { api_request "$1" "$2" "$3" status -u "admin:${ADMIN_PASSWORD}" "${@:4}"; }
admin_api_body() { api_request "$1" "$2" "$3" body -u "admin:${ADMIN_PASSWORD}" "${@:4}"; }

sonar_server_major_version() {
  local version major
  version="$(admin_api_body "$EXIT_PROJECT_POLICY" "project-policy:read-server-version" server -G http://127.0.0.1:9000/api/server/version)" ||
    fail "$EXIT_PROJECT_POLICY" "project-policy:read-server-version failed: endpoint=server"
  major="${version%%.*}"
  [[ "$major" =~ ^[0-9]+$ ]] || fail "$EXIT_PROJECT_POLICY" "project-policy:read-server-version failed: endpoint=server status=invalid-version"
  printf '%s\n' "$major"
}

quality_gate_rating_metric() {
  local metric="$1" sonar_major_version="$2"
  case "$metric" in
    new_reliability_rating)
      if (( sonar_major_version >= 26 )); then
        printf '%s\n' new_software_quality_reliability_rating
      else
        printf '%s\n' new_reliability_rating
      fi
      ;;
    new_security_rating)
      if (( sonar_major_version >= 26 )); then
        printf '%s\n' new_software_quality_security_rating
      else
        printf '%s\n' new_security_rating
      fi
      ;;
    *)
      printf '%s\n' "$metric"
      ;;
  esac
}

quality_gate_conditions_match_exact_policy() {
  local gate_json="$1" required_conditions="$2"
  GATE_JSON="$gate_json" REQUIRED_CONDITIONS="$required_conditions" python3 - <<'PY'
import json, os
from collections import Counter

gate = json.loads(os.environ["GATE_JSON"])
actual = Counter((str(condition.get("metric")), str(condition.get("op")), str(condition.get("error")))
                 for condition in gate.get("conditions", []))
required = Counter(tuple(line.split(":", 2)) for line in os.environ["REQUIRED_CONDITIONS"].splitlines() if line)
raise SystemExit(0 if actual == required else 1)
PY
}

quality_gate_delete_disposable() {
  local delete_status
  admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:deselect-quality-gate" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/deselect --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || true
  delete_status="$(admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:delete-quality-gate" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/destroy --data-urlencode "name=${GATE_NAME}")"
  case "$delete_status" in
    20*|404)
      return 0
      ;;
  esac

  fail "$EXIT_PROJECT_POLICY" "project-policy:delete-quality-gate failed: endpoint=qualitygates status=${delete_status}"
}

quality_gate_condition_ids() {
  local gate_json="$1"
  GATE_JSON="$gate_json" python3 - <<'PY'
import json, os

gate = json.loads(os.environ["GATE_JSON"])
for condition in gate.get("conditions", []):
    condition_id = condition.get("id")
    if condition_id is not None:
        print(condition_id)
PY
}

ensure_project_policy() {
  local gate_json gate_status gate_create_status condition_status permission visibility anyone_browse
  local sonar_major_version reliability_metric security_metric
  sonar_major_version="$(sonar_server_major_version)"
  reliability_metric="$(quality_gate_rating_metric new_reliability_rating "$sonar_major_version")"
  security_metric="$(quality_gate_rating_metric new_security_rating "$sonar_major_version")"
  project_create_status="$(admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:create-project" projects -X POST http://127.0.0.1:9000/api/projects/create --data-urlencode "project=${PROJECT_KEY}" --data-urlencode "name=${PROJECT_KEY}")"
  if [[ "$project_create_status" != 20* && "$project_create_status" != 400 ]]; then
    fail "$EXIT_PROJECT_POLICY" "project-policy:create-project failed: endpoint=projects status=${project_create_status}"
  fi
  admin_api_body "$EXIT_PROJECT_POLICY" "project-policy:make-private" projects -X POST http://127.0.0.1:9000/api/projects/update_visibility --data-urlencode "project=${PROJECT_KEY}" --data-urlencode visibility=private >/dev/null
  # Explicit project scope prevents anonymous Browse even if global settings differ.
  admin_api_status "$EXIT_PERMISSION" "permission:remove-anyone-project" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_group --data-urlencode groupName=Anyone --data-urlencode permission=user --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || true
  visibility="$(admin_api_body "$EXIT_PROJECT_POLICY" "project-policy:verify-visibility" components -G http://127.0.0.1:9000/api/components/show --data-urlencode "component=${PROJECT_KEY}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("component", {}).get("visibility", ""))')" || fail "$EXIT_PROJECT_POLICY" "project-policy:verify-visibility failed: endpoint=components"
  [[ "$visibility" == private ]] || fail "$EXIT_PROJECT_POLICY" "project-policy:verify-visibility failed: endpoint=components status=private-mismatch"
  anyone_browse="$(admin_api_body "$EXIT_PERMISSION" "permission:verify-anyone-browse" permissions -G http://127.0.0.1:9000/api/permissions/groups --data-urlencode permission=user --data-urlencode "projectKey=${PROJECT_KEY}" | python3 -c 'import json,sys; print(any(g.get("name") == "Anyone" for g in json.load(sys.stdin).get("groups", [])))')" || fail "$EXIT_PERMISSION" "permission:verify-anyone-browse failed: endpoint=permissions"
  [[ "$anyone_browse" == False ]] || fail "$EXIT_PERMISSION" "permission:verify-anyone-browse failed: endpoint=permissions status=Anyone-present"
  GATE_NAME="${PROJECT_KEY} Preflight"
  REQUIRED_QUALITY_GATE_CONDITIONS=$(printf 'new_bugs:GT:0\nnew_vulnerabilities:GT:0\n%s:GT:1\n%s:GT:1' "$reliability_metric" "$security_metric")
  gate_needs_rebuild=true
  gate_status="$(admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:read-quality-gate" qualitygates -G http://127.0.0.1:9000/api/qualitygates/show --data-urlencode "name=${GATE_NAME}")" || fail "$EXIT_PROJECT_POLICY" "project-policy:read-quality-gate failed: endpoint=qualitygates"
  case "$gate_status" in
    200)
      gate_json="$(admin_api_body "$EXIT_PROJECT_POLICY" "project-policy:read-quality-gate" qualitygates -G http://127.0.0.1:9000/api/qualitygates/show --data-urlencode "name=${GATE_NAME}")" || fail "$EXIT_PROJECT_POLICY" "project-policy:read-quality-gate failed: endpoint=qualitygates"
      if quality_gate_conditions_match_exact_policy "$gate_json" "$REQUIRED_QUALITY_GATE_CONDITIONS"; then
        admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:bind-quality-gate" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/select --data-urlencode "projectKey=${PROJECT_KEY}" --data-urlencode "gateName=${GATE_NAME}" >/dev/null || fail "$EXIT_PROJECT_POLICY" "project-policy:bind-quality-gate failed: endpoint=qualitygates"
        gate_needs_rebuild=false
      else
        quality_gate_delete_disposable "$gate_json"
      fi
      ;;
    404)
      ;;
    *)
      fail "$EXIT_PROJECT_POLICY" "project-policy:read-quality-gate failed: endpoint=qualitygates status=${gate_status}"
      ;;
  esac

  if [[ "$gate_needs_rebuild" == true ]]; then
    gate_create_status="$(admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:create-quality-gate" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/create --data-urlencode "name=${GATE_NAME}")"
    if [[ "$gate_create_status" != 20* ]]; then
      fail "$EXIT_PROJECT_POLICY" "project-policy:create-quality-gate failed: endpoint=qualitygates status=${gate_create_status}"
    fi
    admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:bind-quality-gate" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/select --data-urlencode "projectKey=${PROJECT_KEY}" --data-urlencode "gateName=${GATE_NAME}" >/dev/null || fail "$EXIT_PROJECT_POLICY" "project-policy:bind-quality-gate failed: endpoint=qualitygates"
    gate_json="$(admin_api_body "$EXIT_PROJECT_POLICY" "project-policy:verify-quality-gate-policy" qualitygates -G http://127.0.0.1:9000/api/qualitygates/show --data-urlencode "name=${GATE_NAME}")" || fail "$EXIT_PROJECT_POLICY" "project-policy:verify-quality-gate-policy failed: endpoint=qualitygates"
    condition_ids="$(quality_gate_condition_ids "$gate_json")" || fail "$EXIT_PROJECT_POLICY" "project-policy:verify-quality-gate-policy failed: endpoint=qualitygates status=invalid-conditions"
    for condition_id in $condition_ids; do
      [[ -z "$condition_id" ]] || admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:delete-new-gate-condition" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/delete_condition --data-urlencode "id=${condition_id}" >/dev/null || fail "$EXIT_PROJECT_POLICY" "project-policy:delete-new-gate-condition failed: endpoint=qualitygates"
    done
    for condition in "new_bugs:GT:0" "new_vulnerabilities:GT:0" "${reliability_metric}:GT:1" "${security_metric}:GT:1"; do
      IFS=: read -r metric op threshold <<<"$condition"
      condition_status="$(admin_api_status "$EXIT_PROJECT_POLICY" "project-policy:create-quality-gate-condition" qualitygates -X POST http://127.0.0.1:9000/api/qualitygates/create_condition --data-urlencode "gateName=${GATE_NAME}" --data-urlencode "metric=${metric}" --data-urlencode "op=${op}" --data-urlencode "error=${threshold}")"
      [[ "$condition_status" == 20* ]] || fail "$EXIT_PROJECT_POLICY" "project-policy:create-quality-gate-condition failed: endpoint=qualitygates status=${condition_status}"
    done
    gate_json="$(admin_api_body "$EXIT_PROJECT_POLICY" "project-policy:verify-quality-gate-policy" qualitygates -G http://127.0.0.1:9000/api/qualitygates/show --data-urlencode "name=${GATE_NAME}")" || fail "$EXIT_PROJECT_POLICY" "project-policy:verify-quality-gate-policy failed: endpoint=qualitygates"
    quality_gate_conditions_match_exact_policy "$gate_json" "$REQUIRED_QUALITY_GATE_CONDITIONS" || fail "$EXIT_PROJECT_POLICY" "project-policy:verify-quality-gate-policy failed: endpoint=qualitygates status=policy-mismatch"
  fi
  for permission in admin codeviewer issueadmin securityhotspotadmin scan user; do
    admin_api_status "$EXIT_PERMISSION" "permission:clear-scanner-project-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode "permission=${permission}" --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || true
    admin_api_status "$EXIT_PERMISSION" "permission:clear-reader-project-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${READER_USER}" --data-urlencode "permission=${permission}" --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || true
  done
  admin_api_body "$EXIT_PERMISSION" "permission:add-scanner-scan" permissions -X POST http://127.0.0.1:9000/api/permissions/add_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode permission=scan --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null
  admin_api_body "$EXIT_PERMISSION" "permission:add-reader-user" permissions -X POST http://127.0.0.1:9000/api/permissions/add_user --data-urlencode "login=${READER_USER}" --data-urlencode permission=user --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null
}

remove_service_account_groups() {
  local account groups group
  for account in "$SCANNER_USER" "$READER_USER"; do
    groups="$(admin_api_body "$EXIT_PERMISSION" "permission:list-user-groups" user_groups -G http://127.0.0.1:9000/api/user_groups/search --data-urlencode "login=${account}" | python3 -c 'import json,sys; [print(g["name"]) for g in json.load(sys.stdin).get("groups", [])]')" || fail "$EXIT_PERMISSION" "permission:list-user-groups failed: endpoint=user_groups"
    while IFS= read -r group; do
      [[ -z "$group" ]] || admin_api_status "$EXIT_PERMISSION" "permission:remove-user-group" user_groups -X POST http://127.0.0.1:9000/api/user_groups/remove_user --data-urlencode "login=${account}" --data-urlencode "name=${group}" >/dev/null || true
    done <<<"$groups"
  done
}

enforce_service_account_least_privilege() {
  local sonar_major_version
  local -a global_permissions=(admin gateadmin profileadmin provisioning scan applicationcreator portfoliocreator)
   local -a project_permissions=(admin codeviewer issueadmin securityhotspotadmin scan user)
  sonar_major_version="$(sonar_server_major_version)"

  if (( sonar_major_version >= 26 )); then
    local permission users_json groups_json
    for permission in "${global_permissions[@]}"; do
      admin_api_status "$EXIT_PERMISSION" "permission:remove-scanner-global-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode "permission=${permission}" >/dev/null || true
      admin_api_status "$EXIT_PERMISSION" "permission:remove-reader-global-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${READER_USER}" --data-urlencode "permission=${permission}" >/dev/null || true
      admin_api_status "$EXIT_PERMISSION" "permission:remove-anyone-global-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_group --data-urlencode groupName=Anyone --data-urlencode "permission=${permission}" >/dev/null || true

      users_json="$(sonar_permission_users_request "$EXIT_PERMISSION" "permission:verify-global-users-${permission}" -u "admin:${ADMIN_PASSWORD}" --data-urlencode "permission=${permission}")" || fail "$EXIT_PERMISSION" "permission:verify-global-users-${permission} failed: endpoint=permissions"
      groups_json="$(sonar_permission_groups_request "$EXIT_PERMISSION" "permission:verify-global-groups-${permission}" -u "admin:${ADMIN_PASSWORD}" --data-urlencode "permission=${permission}")" || fail "$EXIT_PERMISSION" "permission:verify-global-groups-${permission} failed: endpoint=permissions"
      if ! GLOBAL_PERMISSION="$permission" SCANNER_USER="$SCANNER_USER" READER_USER="$READER_USER" USERS_JSON="$users_json" GROUPS_JSON="$groups_json" python3 - <<'PY'
import json, os

scanner = os.environ["SCANNER_USER"]
reader = os.environ["READER_USER"]
users = {u.get("login") for u in json.loads(os.environ["USERS_JSON"]).get("users", [])}
groups = {g.get("name") for g in json.loads(os.environ["GROUPS_JSON"]).get("groups", [])}
if scanner in users or reader in users or "Anyone" in groups:
    raise SystemExit(1)
PY
      then
        fail "$EXIT_PERMISSION" "permission:verify-global-permissions failed: endpoint=permissions"
      fi
    done

    remove_service_account_groups || fail "$EXIT_PERMISSION" "permission:remove-service-account-groups failed: endpoint=user_groups"
    ensure_project_policy || fail "$EXIT_PROJECT_POLICY" "project-policy: ensure_project_policy failed."

    for permission in "${project_permissions[@]}"; do
      users_json="$(sonar_permission_users_request "$EXIT_PERMISSION" "permission:verify-project-users-${permission}" -u "admin:${ADMIN_PASSWORD}" --data-urlencode "permission=${permission}" --data-urlencode "projectKey=${PROJECT_KEY}")" || fail "$EXIT_PERMISSION" "permission:verify-project-users-${permission} failed: endpoint=permissions"
      groups_json="$(sonar_permission_groups_request "$EXIT_PERMISSION" "permission:verify-project-groups-${permission}" -u "admin:${ADMIN_PASSWORD}" --data-urlencode "permission=${permission}" --data-urlencode "projectKey=${PROJECT_KEY}")" || fail "$EXIT_PERMISSION" "permission:verify-project-groups-${permission} failed: endpoint=permissions"
      if ! PERMISSION="$permission" SCANNER_USER="$SCANNER_USER" READER_USER="$READER_USER" USERS_JSON="$users_json" GROUPS_JSON="$groups_json" python3 - <<'PY'
import json, os

permission = os.environ["PERMISSION"]
scanner = os.environ["SCANNER_USER"]
reader = os.environ["READER_USER"]
users = {u.get("login") for u in json.loads(os.environ["USERS_JSON"]).get("users", [])}
groups = {g.get("name") for g in json.loads(os.environ["GROUPS_JSON"]).get("groups", [])}
expected = {"scan": {scanner}, "user": {reader}}.get(permission, set())
if users != expected or "Anyone" in groups:
    raise SystemExit(1)
PY
      then
        fail "$EXIT_PERMISSION" "permission:verify-project-permissions failed: endpoint=permissions"
      fi
    done
  else
    local global_permissions_json project_permissions_json scanner_groups reader_groups
    # Remove direct global rights dynamically so new Sonar permission types do
    # not silently become an exception to this service-account policy.
    global_permissions_json="$(admin_api_body "$EXIT_PERMISSION" "permission:search-global" permissions -G http://127.0.0.1:9000/api/permissions/search)" || fail "$EXIT_PERMISSION" "permission:search-global failed: endpoint=permissions"
    while IFS= read -r permission; do
      [[ -z "$permission" ]] && continue
      admin_api_status "$EXIT_PERMISSION" "permission:remove-scanner-global-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode "permission=${permission}" >/dev/null || true
      admin_api_status "$EXIT_PERMISSION" "permission:remove-reader-global-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${READER_USER}" --data-urlencode "permission=${permission}" >/dev/null || true
      # Anyone is an effective global membership.  It must not grant either
      # account a global right, so remove every global grant to that pseudo-group.
      admin_api_status "$EXIT_PERMISSION" "permission:remove-anyone-global-${permission}" permissions -X POST http://127.0.0.1:9000/api/permissions/remove_group --data-urlencode groupName=Anyone --data-urlencode "permission=${permission}" >/dev/null || true
    done < <(python3 -c 'import json,sys; [print(p["key"]) for p in json.load(sys.stdin).get("permissions", [])]' <<<"$global_permissions_json")

    remove_service_account_groups || fail "$EXIT_PERMISSION" "permission:remove-service-account-groups failed: endpoint=user_groups"
    ensure_project_policy || fail "$EXIT_PROJECT_POLICY" "project-policy: ensure_project_policy failed."

    # The API response is the authoritative effective-permission check: no
    # global direct/Anyone grant, no group membership, and exactly scan/browse
    # as direct permissions on this private project.
    global_permissions_json="$(admin_api_body "$EXIT_PERMISSION" "permission:search-global-verify" permissions -G http://127.0.0.1:9000/api/permissions/search)" || fail "$EXIT_PERMISSION" "permission:search-global-verify failed: endpoint=permissions"
    project_permissions_json="$(admin_api_body "$EXIT_PERMISSION" "permission:search-project-verify" permissions -G http://127.0.0.1:9000/api/permissions/search --data-urlencode "projectKey=${PROJECT_KEY}")" || fail "$EXIT_PERMISSION" "permission:search-project-verify failed: endpoint=permissions"
    for permission in $(python3 -c 'import json,sys; [print(p["key"]) for p in json.load(sys.stdin).get("permissions", [])]' <<<"$global_permissions_json"); do
      users_json="$(sonar_permission_users_request "$EXIT_PERMISSION" "permission:verify-global-users-${permission}" -u "admin:${ADMIN_PASSWORD}" --data-urlencode "permission=${permission}")" || fail "$EXIT_PERMISSION" "permission:verify-global-users-${permission} failed: endpoint=permissions"
      groups_json="$(sonar_permission_groups_request "$EXIT_PERMISSION" "permission:verify-global-groups-${permission}" -u "admin:${ADMIN_PASSWORD}" --data-urlencode "permission=${permission}")" || fail "$EXIT_PERMISSION" "permission:verify-global-groups-${permission} failed: endpoint=permissions"
      if ! GLOBAL_PERMISSION="$permission" SCANNER_USER="$SCANNER_USER" READER_USER="$READER_USER" USERS_JSON="$users_json" GROUPS_JSON="$groups_json" python3 - <<'PY'
import json, os

permission = os.environ["GLOBAL_PERMISSION"]
scanner = os.environ["SCANNER_USER"]
reader = os.environ["READER_USER"]
users = {u.get("login") for u in json.loads(os.environ["USERS_JSON"]).get("users", [])}
groups = {g.get("name") for g in json.loads(os.environ["GROUPS_JSON"]).get("groups", [])}
if scanner in users or reader in users or "Anyone" in groups:
    raise SystemExit(1)
PY
      then
        fail "$EXIT_PERMISSION" "permission:verify-global-permissions failed: endpoint=permissions"
      fi
    done
    if ! SCANNER_USER="$SCANNER_USER" READER_USER="$READER_USER" PROJECT_PERMISSIONS_JSON="$project_permissions_json" python3 - <<'PY'
import json, os

scanner = os.environ["SCANNER_USER"]
reader = os.environ["READER_USER"]
project_permissions = json.loads(os.environ["PROJECT_PERMISSIONS_JSON"]).get("permissions", [])
actual = {account: set() for account in (scanner, reader)}
for permission in project_permissions:
    for account in actual:
        if account in permission.get("users", []):
            actual[account].add(permission.get("key"))
if actual != {scanner: {"scan"}, reader: {"user"}}:
    raise SystemExit(1)
PY
    then
      fail "$EXIT_PERMISSION" "permission:verify-project-permissions failed: endpoint=permissions"
    fi
  fi
}

NEEDS_ROTATION=false
if [[ "$(token_login "$ANALYSIS_TOKEN" 2>/dev/null || true)" != "$SCANNER_USER" || "$(token_login "$READ_TOKEN" 2>/dev/null || true)" != "$READER_USER" ]]; then
  NEEDS_ROTATION=true
  SCANNER_PASSWORD="$(random_secret)"; READER_PASSWORD="$(random_secret)"
  SCANNER_ROTATED=false
  READER_ROTATED=false
  if bootstrap_service_account "$SCANNER_USER" "scanner" SCANNER_PASSWORD; then SCANNER_ROTATED=true; fi
  if bootstrap_service_account "$READER_USER" "reader" READER_PASSWORD; then READER_ROTATED=true; fi
fi

ensure_project_policy || fail "$EXIT_PROJECT_POLICY" "project-policy: ensure_project_policy failed."

enforce_service_account_least_privilege || fail "$EXIT_PERMISSION" "permission: enforce_service_account_least_privilege failed."

if [[ "$NEEDS_ROTATION" == true ]]; then
  # Revoke every old token for these service accounts before generating new ones.
  if [[ "${SCANNER_ROTATED:-false}" == true ]]; then
    for token_name in $(admin_api_body "$EXIT_TOKEN" "token:list-scanner" user_tokens -G http://127.0.0.1:9000/api/user_tokens/search --data-urlencode "login=${SCANNER_USER}" | python3 -c 'import json,sys; [print(t["name"]) for t in json.load(sys.stdin).get("userTokens", [])]'); do
      admin_api_body "$EXIT_TOKEN" "token:revoke-scanner" user_tokens -X POST http://127.0.0.1:9000/api/user_tokens/revoke --data-urlencode "login=${SCANNER_USER}" --data-urlencode "name=${token_name}" >/dev/null
    done || fail "$EXIT_TOKEN" "token: revoke existing scanner tokens failed."
    token_json="$(api_request "$EXIT_TOKEN" "token:generate-scanner" user_tokens body -u "${SCANNER_USER}:${SCANNER_PASSWORD}" -X POST http://127.0.0.1:9000/api/user_tokens/generate --data-urlencode "name=scan_${PROJECT_KEY}")"
    ANALYSIS_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("token", ""))' <<<"$token_json")"
  fi
  if [[ "${READER_ROTATED:-false}" == true ]]; then
    for token_name in $(admin_api_body "$EXIT_TOKEN" "token:list-reader" user_tokens -G http://127.0.0.1:9000/api/user_tokens/search --data-urlencode "login=${READER_USER}" | python3 -c 'import json,sys; [print(t["name"]) for t in json.load(sys.stdin).get("userTokens", [])]'); do
      admin_api_body "$EXIT_TOKEN" "token:revoke-reader" user_tokens -X POST http://127.0.0.1:9000/api/user_tokens/revoke --data-urlencode "login=${READER_USER}" --data-urlencode "name=${token_name}" >/dev/null
    done || fail "$EXIT_TOKEN" "token: revoke existing reader tokens failed."
    token_json="$(api_request "$EXIT_TOKEN" "token:generate-reader" user_tokens body -u "${READER_USER}:${READER_PASSWORD}" -X POST http://127.0.0.1:9000/api/user_tokens/generate --data-urlencode "name=read_${PROJECT_KEY}")"
    READ_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("token", ""))' <<<"$token_json")"
  fi
  if [[ "${SCANNER_ROTATED:-false}" == true || "${READER_ROTATED:-false}" == true ]]; then
    [[ -n "$ANALYSIS_TOKEN" && -n "$READ_TOKEN" ]] || fail "$EXIT_TOKEN" "token: least-privilege token bootstrap failed."
  fi
fi
write_analysis_file
write_read_file
unset ADMIN_PASSWORD SCANNER_PASSWORD READER_PASSWORD token_json

# Credentials are now rotated/validated. Only now is the loopback UI published.
  compose_exposed up -d >/dev/null || fail "$EXIT_START" "start: exposed docker compose up failed."
  printf 'SONAR_URL=%s\n' "$SONAR_URL"
