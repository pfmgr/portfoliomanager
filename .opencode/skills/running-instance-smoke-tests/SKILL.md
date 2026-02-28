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

- Stack is running (`docker compose up -d`).
- Credentials are available in `.env` (`ADMIN_USER`, `ADMIN_PASS`).

## Smoke test flow

1. Health endpoint:
   - `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8089/auth/health`
   - Expect `200`.
2. Login and capture JWT:
   - `curl -s -X POST http://127.0.0.1:8089/auth/token -H "Content-Type: application/json" -d '{"username":"<ADMIN_USER>","password":"<ADMIN_PASS>"}'`
3. Access protected endpoint with JWT:
   - `curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:8089/api/rulesets`
   - Expect `200`.
4. Logout:
   - `curl -s -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:8089/auth/logout`
   - Expect `204`.
5. Reuse revoked token:
   - same protected endpoint call as step 3
   - Expect `401`.
