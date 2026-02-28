---
name: frontend-vitest-tests
description: Run frontend unit tests with Vitest
---

## When to use

Use this skill for frontend unit test validation after Vue/UI/API-client changes.

## Scenario-first test scope

- Use relevant `scenarios/*.md` to decide what to test.
- Cover scenario-driven user flows, state handling, and edge cases in Vitest.
- If behavior changed but scenario docs are outdated, update scenarios first or in the same change.

## Preferred command

- `services/app/frontend/scripts/run-unit-tests.sh`

## Manual command

- `cd services/app/frontend`
- `npm test`

## Notes

- Frontend tooling is aligned to Node `v25.3.0`.
