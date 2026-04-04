#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck disable=SC1091
. "${ROOT_DIR}/../scripts/stack-env.sh"

"${ROOT_DIR}/../scripts/check-running-stack-ready.sh" >/dev/null

cd "${ROOT_DIR}"

fixtures_seeded=false

if [ "$#" -gt 0 ]; then
  set -- "$@"
elif [ -n "${RUNNING_STACK_PLAYWRIGHT_SPEC:-}" ]; then
  set -- "${RUNNING_STACK_PLAYWRIGHT_SPEC}"
else
  "${ROOT_DIR}/../scripts/seed-running-stack-fixtures.sh" >/dev/null
  fixtures_seeded=true
  set -- \
    "tests/e2e/login.spec.js" \
    "tests/e2e/assessor.runtime.spec.js" \
    "tests/e2e/rebalancer.runtime.spec.js"
fi

if [ "${RUNNING_STACK_SEED_FIXTURES:-false}" = "true" ] && [ "${fixtures_seeded}" != "true" ]; then
  "${ROOT_DIR}/../scripts/seed-running-stack-fixtures.sh" >/dev/null
fi

E2E_BASE_URL="${FRONTEND_BASE_URL}" \
AUTH_HEALTH_URL="${AUTH_HEALTH_URL}" \
ADMIN_USER="${ADMIN_USER:-}" \
ADMIN_PASS="${ADMIN_PASS:-}" \
npx --no-install playwright test "$@"
