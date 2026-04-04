---
name: running-instance-smoke-tests
description: Verify auth and protected API behavior against a running stack
---

## When to use

Use this skill for quick runtime validation against a live local instance.

## Scenario-first test scope

- Choose smoke endpoints and checks based on relevant `scenarios/*.md`.
- At minimum, verify the changed scenario flow and one relevant edge condition.
- If runtime behavior differs from scenarios, update scenarios in the same change.

## Preconditions

- Stack is running from repo root `docker-compose.yml`.
- Credentials are available in `.env` (`ADMIN_USER`, `ADMIN_PASS`).
- Host and port bindings may differ by `.env`; do not hardcode `127.0.0.1:8089` when executing this skill.

## Preferred command

- `services/app/scripts/run-running-stack-smoke.sh`
- This loads `.env`, derives frontend/backend URLs from the current standard-stack bindings, checks readiness, performs login, calls a protected API, logs out, and verifies token revocation.

## Script building blocks

- Readiness only:
  - `services/app/scripts/check-running-stack-ready.sh`
- Login and print JWT:
  - `services/app/scripts/auth-login.sh`
- Override the protected endpoint when the scenario needs a different protected check:
  - `SMOKE_PROTECTED_PATH=/api/<scenario-path> services/app/scripts/run-running-stack-smoke.sh`

## Manual alternative

1. Load `.env` and resolve the current auth URL from the standard-stack bindings.
2. Check the auth health endpoint.
3. Login with `.env` credentials and capture the returned JWT from `$.token`.
4. Call a protected endpoint with `Authorization: Bearer <TOKEN>` and expect `200`.
5. Logout and expect `204`.
6. Reuse the revoked token and expect `401`.
