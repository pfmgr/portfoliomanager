#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/stack-env.sh"

PROTECTED_PATH="${SMOKE_PROTECTED_PATH:-/api/rulesets}"
PROTECTED_URL="${SMOKE_PROTECTED_URL:-${API_BASE_URL}${PROTECTED_PATH#/api}}"

"${SCRIPT_DIR}/check-running-stack-ready.sh" >/dev/null

token="$("${SCRIPT_DIR}/auth-login.sh")"

protected_status="$(curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${token}" "${PROTECTED_URL}")"
if [ "${protected_status}" != "200" ]; then
  printf 'Protected endpoint check failed for %s with status %s.\n' "${PROTECTED_URL}" "${protected_status}" >&2
  exit 1
fi

logout_status="$(curl -sS -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer ${token}" "${AUTH_LOGOUT_URL}")"
if [ "${logout_status}" != "204" ]; then
  printf 'Logout failed with status %s.\n' "${logout_status}" >&2
  exit 1
fi

revoked_status="$(curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${token}" "${PROTECTED_URL}")"
if [ "${revoked_status}" != "401" ]; then
  printf 'Revoked token check failed with status %s.\n' "${revoked_status}" >&2
  exit 1
fi

printf 'Running stack smoke checks passed.\n'
printf 'Auth health: %s\n' "${AUTH_HEALTH_URL}"
printf 'Protected endpoint: %s\n' "${PROTECTED_URL}"
