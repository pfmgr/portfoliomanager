---
name: frontend-running-stack-e2e-tests
description: Run Playwright E2E tests against the running standard stack
---

## When to use

Use this skill only when the running standard stack is explicitly approved for the task, the environment is documented as loopback / non-production, and the verification does not require fixture seeding or destructive lifecycle commands. If any of those conditions is missing, use `frontend-e2e-tests` against the isolated stack instead.

## Scenario-first test scope

- Select runtime browser flows from relevant `scenarios/*.md`.
- Prefer stable happy paths and one meaningful edge path per changed scenario.
- Keep runtime specs separate from stubbed UI specs so agents can run them selectively.
- If runtime behavior differs from scenarios, update the impacted scenario docs in the same task.

## Preconditions

- The standard stack may only be reused after explicit release for this task.
- `.env` contains valid `ADMIN_USER` and `ADMIN_PASS` values for the running stack.
- Host and port bindings may vary through `.env`; do not hardcode frontend or backend URLs when this skill is used.
- Do not use this skill when the scenario requires fixture seeding or any destructive stack lifecycle action.

## Preferred command

- `cd services/app/frontend && npm run test:e2e:running-stack`
- This resolves URLs and credentials from `.env`, checks readiness through `services/app/scripts/check-running-stack-ready.sh`, and runs Playwright against the already approved running stack only.

## Run a specific runtime spec

- `cd services/app/frontend && ./scripts/run-running-stack-e2e.sh tests/e2e/<runtime-spec>.js`
- Use this when only one running-stack scenario should be verified.
- Do not add fixture-seeding flags or prerequisite fixture-seeding commands here; switch to `frontend-e2e-tests` if the scenario depends on seeded data.

## Supporting commands

- Readiness only:
  - `services/app/scripts/check-running-stack-ready.sh`
- Auth smoke before browser execution:
  - `services/app/scripts/run-running-stack-smoke.sh`
- If fixture seeding or stack recreation is required, use `frontend-e2e-tests` on the isolated stack instead.

## Notes

- `frontend-e2e-tests` is the default choice for Playwright verification and uses the isolated E2E stack under `services/app/frontend/docker-compose.yml`.
- This skill is only for an explicitly released, already running standard stack under repo root `docker-compose.yml`.
