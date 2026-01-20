#!/usr/bin/env bash
set -euo pipefail
if [[ -f .env ]]; then
	export $(grep -v '^#' .env | xargs)
else
  export PORTFOLIO_MANAGER_VERSION=latest
fi

(cd services/app/backend && ./build-docker.sh)
(cd services/app/frontend && ./build-docker.sh)

