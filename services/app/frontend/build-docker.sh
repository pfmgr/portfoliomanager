#!/usr/bin/env bash
if [[ -z "$PORTFOLIO_MANAGER_VERSION" ]]; then
	export PORTFOLIO_MANAGER_VERSION=latest
fi
docker build -t fg1212/portfoliomanager-frontend:$PORTFOLIO_MANAGER_VERSION .
