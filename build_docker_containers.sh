#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/services/app/scripts/stack-env.sh"

export PORTFOLIO_MANAGER_VERSION="${PORTFOLIO_MANAGER_VERSION:-latest}"

(cd services/app/backend && ./build-docker.sh)
(cd services/app/frontend && ./build-docker.sh)
