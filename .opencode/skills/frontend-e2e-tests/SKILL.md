---
name: frontend-e2e-tests
description: Run Playwright E2E tests against the isolated test stack
---

## When to use

Use this skill for end-to-end verification of UI flows across frontend + backend.

## Scenario-first test scope

- Select E2E flows from relevant `scenarios/*.md`.
- Ensure happy path and edge-case paths described in scenarios are represented.
- If scenarios do not reflect current behavior, update/add scenario docs in the same task.

## Preferred command

- `services/app/frontend/scripts/run-e2e.sh`
- This builds the isolated E2E stack, waits for readiness, runs tests, and tears down.

## Manual alternative

- Start stack:
  - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e up -d --build`
- Check logs if needed:
  - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e logs --tail=200 e2e_db_portfolio`
  - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e logs --tail=200 e2e_admin_spring`
  - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e logs --tail=200 e2e_admin_frontend`
- Tear down stack:
  - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e down -v`
