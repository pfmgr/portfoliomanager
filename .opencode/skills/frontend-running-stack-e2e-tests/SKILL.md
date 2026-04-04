---
name: frontend-running-stack-e2e-tests
description: Run Playwright E2E tests against the running standard stack
---

## When to use

Use this skill when a local standard stack is already running via the repo root `docker-compose.yml` and the goal is to verify real browser flows without rebuilding or replacing that environment.

## Scenario-first test scope

- Select runtime browser flows from relevant `scenarios/*.md`.
- Prefer stable happy paths and one meaningful edge path per changed scenario.
- Keep runtime specs separate from stubbed UI specs so agents can run them selectively.
- If runtime behavior differs from scenarios, update the impacted scenario docs in the same task.

## Preconditions

- The standard stack is already running from repo root `docker-compose.yml`.
- `.env` contains valid `ADMIN_USER` and `ADMIN_PASS` values for the running stack.
- Host and port bindings may vary through `.env`; do not hardcode frontend or backend URLs when this skill is used.
- If the scenario depends on seeded assessor or rebalancer runtime data, run `running-stack-fixtures` first.

## Preferred command

- `cd services/app/frontend && npm run test:e2e:running-stack`
- This resolves URLs and credentials from `.env`, checks readiness through `services/app/scripts/check-running-stack-ready.sh`, seeds the dedicated running-stack fixture rows for the default assessor/rebalancer runtime specs, and runs Playwright against the existing stack.

## Run a specific runtime spec

- `cd services/app/frontend && ./scripts/run-running-stack-e2e.sh tests/e2e/<runtime-spec>.js`
- Use this when only one running-stack scenario should be verified.
- If the selected runtime spec depends on seeded fixture data, add `RUNNING_STACK_SEED_FIXTURES=true` or run `running-stack-fixtures` first.

## Supporting commands

- Readiness only:
  - `services/app/scripts/check-running-stack-ready.sh`
- Auth smoke before browser execution:
  - `services/app/scripts/run-running-stack-smoke.sh`
- Seed reproducible runtime fixtures:
  - `services/app/scripts/seed-running-stack-fixtures.sh`

## Notes

- This skill complements `frontend-e2e-tests`.
- `frontend-e2e-tests` is for the isolated E2E stack under `services/app/frontend/docker-compose.yml`.
- This skill is for the already running standard stack under repo root `docker-compose.yml`.
