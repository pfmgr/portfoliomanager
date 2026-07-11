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

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-key) PROJECT_KEY="${2:?missing project key}"; shift 2 ;;
    --port) PORT_OVERRIDE="${2:?missing port}"; shift 2 ;;
    --timeout) TIMEOUT_SECONDS="${2:?missing timeout}"; shift 2 ;;
    --quiet) QUIET=true; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 4 ;;
  esac
done
log() { [[ "$QUIET" == true ]] || printf '%s\n' "$*" >&2; }
command -v python3 >/dev/null || { echo "Python 3 is required." >&2; exit 4; }
[[ -f "$COMPOSE_FILE" && -f "$EXPOSE_COMPOSE_FILE" ]] || { echo "Sonar Compose files are missing." >&2; exit 4; }
PROJECT_KEY="${PROJECT_KEY:-$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '_' | sed 's/_$//')}"
[[ "$PROJECT_KEY" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { echo "Invalid Sonar project key." >&2; exit 4; }
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
  prepare_runtime_dir || { echo "Unsafe Sonar runtime directory." >&2; exit 4; }
  [[ ! -L "$path" ]] || { echo "Unsafe Sonar runtime file." >&2; exit 4; }
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
random_secret() { python3 -c 'import secrets; print(secrets.token_urlsafe(48))'; }
port_free() { python3 - "$1" <<'PY'
import socket, sys
s=socket.socket()
try: s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError: raise SystemExit(1)
finally: s.close()
PY
}

# Runtime credentials are generated and retained only in protected local files.
for forbidden in SONAR_TOKEN SONAR_ADMIN_TOKEN SONAR_ANALYSIS_TOKEN SONAR_READ_TOKEN; do
  if [[ -v "$forbidden" ]]; then
    echo "Externally supplied ${forbidden} is not accepted as a Sonar runtime token; preflight blocked." >&2
    exit 4
  fi
done
ANALYSIS_TOKEN=""
READ_TOKEN=""
if [[ -f "$ANALYSIS_ENV" ]]; then ANALYSIS_TOKEN="$(safe_value "$ANALYSIS_ENV" SONAR_ANALYSIS_TOKEN)" || { echo "Unsafe analysis runtime file." >&2; exit 4; }; fi
if [[ -f "$READ_ENV" ]]; then READ_TOKEN="$(safe_value "$READ_ENV" SONAR_READ_TOKEN)" || { echo "Unsafe read runtime file." >&2; exit 4; }; fi
SONAR_PORT="$PORT_OVERRIDE"
if [[ -z "$SONAR_PORT" && -f "$READ_ENV" ]]; then SONAR_URL="$(safe_value "$READ_ENV" SONAR_URL)" || exit 4; SONAR_PORT="${SONAR_URL##*:}"; fi
if [[ -z "$SONAR_PORT" ]]; then for port in $(seq 9000 9100); do port_free "$port" && { SONAR_PORT="$port"; break; }; done; fi
[[ "$SONAR_PORT" =~ ^[0-9]+$ ]] && (( SONAR_PORT > 0 && SONAR_PORT < 65536 )) || { echo "No valid free SonarQube port found." >&2; exit 4; }
SONAR_URL="http://127.0.0.1:${SONAR_PORT}"
export REPO_PREFIX SONAR_PORT
docker compose -f "$COMPOSE_FILE" -p "$COMPOSE_PROJECT_NAME" config >/dev/null || { echo "Sonar Compose configuration is invalid." >&2; exit 4; }

require_local_image() {
  local approved="$1" digests
  if ! digests="$(docker image inspect --format '{{join .RepoDigests "\n"}}' "$approved" 2>/dev/null)" || ! grep -Fxq "$approved" <<<"$digests"; then
    echo "blocked: approved image is unavailable locally: ${approved}; automatic pulls are disabled." >&2
    exit 4
  fi
}
require_local_image "postgres@sha256:056b54f00419b49289227ab12d09df508543883f407fe9935a2cec430ef8aa8d"
require_local_image "sonarqube@sha256:24d75d7e0021f2d0f94e4d761b734088b1afc00395d161a7035d61df5a812f5b"

# No host port exists during this phase.
docker compose -f "$COMPOSE_FILE" -p "$COMPOSE_PROJECT_NAME" up -d
CONTAINER_ID="$(docker compose -f "$COMPOSE_FILE" -p "$COMPOSE_PROJECT_NAME" ps -q sonarqube)"
[[ -n "$CONTAINER_ID" ]] || { echo "SonarQube container was not created." >&2; exit 4; }
sonar_curl() { docker exec -i "$CONTAINER_ID" curl --connect-timeout 5 --max-time 30 -sf "$@"; }
deadline=$((SECONDS + TIMEOUT_SECONDS))
until sonar_curl http://127.0.0.1:9000/api/system/status 2>/dev/null | grep -q '"status":"UP"'; do
  (( SECONDS < deadline )) || { echo "SonarQube did not become ready; preflight blocked." >&2; exit 4; }
  sleep 2
done

token_login() { sonar_curl -H "Authorization: Bearer $1" http://127.0.0.1:9000/api/authentication/validate | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get("login", "") if data.get("valid") else "")'; }
SCANNER_USER="opencode_scan_${PROJECT_KEY}"
READER_USER="opencode_read_${PROJECT_KEY}"

if [[ -f "$BOOTSTRAP_ENV" ]]; then
    ADMIN_PASSWORD="$(safe_value "$BOOTSTRAP_ENV" SONAR_BOOTSTRAP_PASSWORD)" || { echo "Unsafe Sonar bootstrap runtime file." >&2; exit 4; }
    [[ -n "$ADMIN_PASSWORD" ]] || { echo "Missing persistent Sonar bootstrap data; preflight blocked." >&2; exit 4; }
    admin_api() { sonar_curl -u "admin:${ADMIN_PASSWORD}" "$@"; }
else
    ADMIN_PASSWORD="$(random_secret)"
    sonar_curl -u 'admin:admin' -X POST http://127.0.0.1:9000/api/users/change_password --data-urlencode login=admin --data-urlencode previousPassword=admin --data-urlencode "password=${ADMIN_PASSWORD}" >/dev/null || { echo "No local bootstrap credential exists and the default admin password was rejected; preflight blocked." >&2; exit 4; }
    admin_api() { sonar_curl -u "admin:${ADMIN_PASSWORD}" "$@"; }
    write_bootstrap_file "$ADMIN_PASSWORD"
fi

ensure_project_policy() {
  local gate_json condition_ids permission visibility anyone_browse
  admin_api -X POST http://127.0.0.1:9000/api/projects/create --data-urlencode "project=${PROJECT_KEY}" --data-urlencode "name=${PROJECT_KEY}" >/dev/null || true
  admin_api -X POST http://127.0.0.1:9000/api/projects/update_visibility --data-urlencode "project=${PROJECT_KEY}" --data-urlencode visibility=private >/dev/null || return 1
  # Explicit project scope prevents anonymous Browse even if global settings differ.
  admin_api -X POST http://127.0.0.1:9000/api/permissions/remove_group --data-urlencode groupName=Anyone --data-urlencode permission=user --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || return 1
  visibility="$(admin_api -G http://127.0.0.1:9000/api/components/show --data-urlencode "component=${PROJECT_KEY}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("component", {}).get("visibility", ""))')" || return 1
  [[ "$visibility" == private ]] || return 1
  anyone_browse="$(admin_api -G http://127.0.0.1:9000/api/permissions/groups --data-urlencode permission=user --data-urlencode "projectKey=${PROJECT_KEY}" | python3 -c 'import json,sys; print(any(g.get("name") == "Anyone" for g in json.load(sys.stdin).get("groups", [])))')" || return 1
  [[ "$anyone_browse" == False ]] || return 1
  GATE_NAME="${PROJECT_KEY} Preflight"
  admin_api -X POST http://127.0.0.1:9000/api/qualitygates/create --data-urlencode "name=${GATE_NAME}" >/dev/null || true
  admin_api -X POST http://127.0.0.1:9000/api/qualitygates/select --data-urlencode "projectKey=${PROJECT_KEY}" --data-urlencode "gateName=${GATE_NAME}" >/dev/null || return 1
  gate_json="$(admin_api -G http://127.0.0.1:9000/api/qualitygates/show --data-urlencode "name=${GATE_NAME}")" || return 1
  condition_ids="$(GATE_JSON="$gate_json" python3 - <<'PY'
import json, os
for condition in json.loads(os.environ['GATE_JSON']).get('conditions', []):
    if condition.get('id') is not None: print(condition['id'])
PY
)" || return 1
  while IFS= read -r condition_id; do
    [[ -z "$condition_id" ]] || admin_api -X POST http://127.0.0.1:9000/api/qualitygates/delete_condition --data-urlencode "id=${condition_id}" >/dev/null || return 1
  done <<<"$condition_ids"
  for condition in 'new_bugs:GT:0' 'new_vulnerabilities:GT:0' 'new_reliability_rating:GT:1' 'new_security_rating:GT:1'; do
    IFS=: read -r metric op threshold <<<"$condition"
    admin_api -X POST http://127.0.0.1:9000/api/qualitygates/create_condition --data-urlencode "gateName=${GATE_NAME}" --data-urlencode "metric=${metric}" --data-urlencode "op=${op}" --data-urlencode "error=${threshold}" >/dev/null || return 1
  done
  for permission in admin codeviewer issueadmin scan user; do
    admin_api -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode "permission=${permission}" --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || true
    admin_api -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${READER_USER}" --data-urlencode "permission=${permission}" --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || true
  done
  admin_api -X POST http://127.0.0.1:9000/api/permissions/add_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode permission=scan --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || return 1
  admin_api -X POST http://127.0.0.1:9000/api/permissions/add_user --data-urlencode "login=${READER_USER}" --data-urlencode permission=user --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null || return 1
}

remove_service_account_groups() {
  local account groups group
  for account in "$SCANNER_USER" "$READER_USER"; do
    groups="$(admin_api -G http://127.0.0.1:9000/api/user_groups/search --data-urlencode "login=${account}" | python3 -c 'import json,sys; [print(g["name"]) for g in json.load(sys.stdin).get("groups", [])]')" || return 1
    while IFS= read -r group; do
      [[ -z "$group" ]] || admin_api -X POST http://127.0.0.1:9000/api/user_groups/remove_user --data-urlencode "login=${account}" --data-urlencode "name=${group}" >/dev/null || return 1
    done <<<"$groups"
  done
}

enforce_service_account_least_privilege() {
  local global_permissions permission
  # Remove direct global rights dynamically so new Sonar permission types do
  # not silently become an exception to this service-account policy.
  global_permissions="$(admin_api -G http://127.0.0.1:9000/api/permissions/search)" || return 1
  while IFS= read -r permission; do
    [[ -z "$permission" ]] && continue
    admin_api -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${SCANNER_USER}" --data-urlencode "permission=${permission}" >/dev/null || return 1
    admin_api -X POST http://127.0.0.1:9000/api/permissions/remove_user --data-urlencode "login=${READER_USER}" --data-urlencode "permission=${permission}" >/dev/null || return 1
    # Anyone is an effective global membership.  It must not grant either
    # account a global right, so remove every global grant to that pseudo-group.
    admin_api -X POST http://127.0.0.1:9000/api/permissions/remove_group --data-urlencode groupName=Anyone --data-urlencode "permission=${permission}" >/dev/null || return 1
  done < <(python3 -c 'import json,sys; [print(p["key"]) for p in json.load(sys.stdin).get("permissions", [])]' <<<"$global_permissions")

  remove_service_account_groups || return 1
  ensure_project_policy || return 1

  # The API response is the authoritative effective-permission check: no
  # global direct/Anyone grant, no group membership, and exactly scan/browse
  # as direct permissions on this private project.
  global_permissions="$(admin_api -G http://127.0.0.1:9000/api/permissions/search)" || return 1
  project_permissions="$(admin_api -G http://127.0.0.1:9000/api/permissions/search --data-urlencode "projectKey=${PROJECT_KEY}")" || return 1
  scanner_groups="$(admin_api -G http://127.0.0.1:9000/api/user_groups/search --data-urlencode "login=${SCANNER_USER}")" || return 1
  reader_groups="$(admin_api -G http://127.0.0.1:9000/api/user_groups/search --data-urlencode "login=${READER_USER}")" || return 1
  SCANNER_USER="$SCANNER_USER" READER_USER="$READER_USER" GLOBAL_PERMISSIONS="$global_permissions" PROJECT_PERMISSIONS="$project_permissions" SCANNER_GROUPS="$scanner_groups" READER_GROUPS="$reader_groups" python3 - <<'PY'
import json, os

scanner = os.environ["SCANNER_USER"]
reader = os.environ["READER_USER"]
global_permissions = json.loads(os.environ["GLOBAL_PERMISSIONS"]).get("permissions", [])
project_permissions = json.loads(os.environ["PROJECT_PERMISSIONS"]).get("permissions", [])
groups = json.loads(os.environ["SCANNER_GROUPS"]).get("groups", []) + json.loads(os.environ["READER_GROUPS"]).get("groups", [])
if any(scanner in p.get("users", []) or reader in p.get("users", []) or "Anyone" in p.get("groups", []) for p in global_permissions):
    raise SystemExit(1)
if groups:
    raise SystemExit(1)
actual = {account: set() for account in (scanner, reader)}
for permission in project_permissions:
    for account in actual:
        if account in permission.get("users", []):
            actual[account].add(permission.get("key"))
if actual != {scanner: {"scan"}, reader: {"user"}}:
    raise SystemExit(1)
PY
}

NEEDS_ROTATION=false
if [[ "$(token_login "$ANALYSIS_TOKEN" 2>/dev/null || true)" != "$SCANNER_USER" || "$(token_login "$READ_TOKEN" 2>/dev/null || true)" != "$READER_USER" ]]; then
  NEEDS_ROTATION=true
  SCANNER_PASSWORD="$(random_secret)"; READER_PASSWORD="$(random_secret)"
  admin_api -X POST http://127.0.0.1:9000/api/users/create --data-urlencode "login=${SCANNER_USER}" --data-urlencode "name=${SCANNER_USER}" --data-urlencode "password=${SCANNER_PASSWORD}" >/dev/null || true
  admin_api -X POST http://127.0.0.1:9000/api/users/create --data-urlencode "login=${READER_USER}" --data-urlencode "name=${READER_USER}" --data-urlencode "password=${READER_PASSWORD}" >/dev/null || true
  admin_api -X POST http://127.0.0.1:9000/api/users/change_password --data-urlencode "login=${SCANNER_USER}" --data-urlencode "password=${SCANNER_PASSWORD}" >/dev/null
  admin_api -X POST http://127.0.0.1:9000/api/users/change_password --data-urlencode "login=${READER_USER}" --data-urlencode "password=${READER_PASSWORD}" >/dev/null
fi

ensure_project_policy || { echo "Unable to enforce private project, permissions, or exact pre-test gate policy; preflight blocked." >&2; exit 4; }

enforce_service_account_least_privilege || { echo "Unable to remove or verify global/group service-account rights and minimal project permissions; preflight blocked." >&2; exit 4; }

if [[ "$NEEDS_ROTATION" == true ]]; then
  # Revoke every old token for these service accounts before generating new ones.
  for account in "$SCANNER_USER" "$READER_USER"; do
    admin_api -G http://127.0.0.1:9000/api/user_tokens/search --data-urlencode "login=${account}" | python3 -c 'import json,sys; [print(t["name"]) for t in json.load(sys.stdin).get("userTokens", [])]' | while IFS= read -r token_name; do
      admin_api -X POST http://127.0.0.1:9000/api/user_tokens/revoke --data-urlencode "login=${account}" --data-urlencode "name=${token_name}" >/dev/null || exit 1
    done || { echo "Unable to revoke existing service-account tokens; preflight blocked." >&2; exit 4; }
  done
  token_json="$(sonar_curl -u "${SCANNER_USER}:${SCANNER_PASSWORD}" -X POST http://127.0.0.1:9000/api/user_tokens/generate --data-urlencode "name=scan_${PROJECT_KEY}")"
  ANALYSIS_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("token", ""))' <<<"$token_json")"
  token_json="$(sonar_curl -u "${READER_USER}:${READER_PASSWORD}" -X POST http://127.0.0.1:9000/api/user_tokens/generate --data-urlencode "name=read_${PROJECT_KEY}")"
  READ_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("token", ""))' <<<"$token_json")"
  [[ -n "$ANALYSIS_TOKEN" && -n "$READ_TOKEN" ]] || { echo "Least-privilege token bootstrap failed; preflight blocked." >&2; exit 4; }
fi
write_analysis_file
write_read_file
unset ADMIN_PASSWORD SCANNER_PASSWORD READER_PASSWORD token_json

# Credentials are now rotated/validated. Only now is the loopback UI published.
docker compose -f "$COMPOSE_FILE" -f "$EXPOSE_COMPOSE_FILE" -p "$COMPOSE_PROJECT_NAME" up -d >/dev/null
printf 'SONAR_URL=%s\n' "$SONAR_URL"
