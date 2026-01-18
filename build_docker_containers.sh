#!/usr/bin/env bash
set -euo pipefail
(cd services/app/backend && ./build-docker.sh)
(cd services/app/frontend && ./build-docker.sh)

