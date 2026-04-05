#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/stack-env.sh"

USERNAME="${LOGIN_USER:-${ADMIN_USER:-}}"
PASSWORD="${LOGIN_PASS:-${ADMIN_PASS:-}}"

if [ -z "${USERNAME}" ] || [ -z "${PASSWORD}" ]; then
  printf 'ADMIN_USER and ADMIN_PASS must be configured via .env or env vars.\n' >&2
  exit 1
fi

payload="$(LOGIN_USER="${USERNAME}" LOGIN_PASS="${PASSWORD}" node -e 'process.stdout.write(JSON.stringify({ username: process.env.LOGIN_USER, password: process.env.LOGIN_PASS }))')"

if [ "${STACK_ALLOW_INSECURE_TLS:-false}" = "true" ]; then
  response="$(curl -k -sS -X POST "${AUTH_TOKEN_URL}" -H "Content-Type: application/json" -d "${payload}")"
else
  response="$(curl -sS -X POST "${AUTH_TOKEN_URL}" -H "Content-Type: application/json" -d "${payload}")"
fi

token="$({ printf '%s' "${response}" | node -e 'let body="";process.stdin.on("data", chunk => body += chunk);process.stdin.on("end", () => { try { const parsed = JSON.parse(body); if (!parsed.token) { process.exit(2); } process.stdout.write(parsed.token); } catch (error) { process.exit(3); } });'; } 2>/dev/null || true)"

if [ -z "${token}" ]; then
  printf 'Login did not return a token.\n' >&2
  printf '%s\n' "${response}" >&2
  exit 1
fi

printf '%s\n' "${token}"
