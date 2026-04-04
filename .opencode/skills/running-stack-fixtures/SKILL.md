---
name: running-stack-fixtures
description: Seed and reset reproducible fixtures on the running standard stack
---

## When to use

Use this skill when runtime browser or API tests against the already running standard stack need a predictable baseline without rebuilding the stack.

## Scope

- This skill manages only clearly named runtime test fixtures.
- It must not wipe arbitrary user data.
- Current fixture set seeds assessor and rebalancer runtime checks with dedicated test depots and test ISINs.

## Seeded identifiers

- Depots:
  - `tst_assr / Runtime Assessor Depot`
  - `tst_reb / Runtime Rebalancer Depot`
- ISINs:
  - `ZZTESTAAA001`
  - `ZZTESTBBB002`
  - `ZZTESTRBL003`

## Preconditions

- The standard stack is already running from repo root `docker-compose.yml`.
- The `db_portfolio` service is reachable through Docker Compose.
- `.env` points to the intended running stack.

## Preferred commands

- Reset only:
  - `services/app/scripts/reset-running-stack-fixtures.sh`
- Seed after cleanup:
  - `services/app/scripts/seed-running-stack-fixtures.sh`

## Recommended workflow

1. Run `services/app/scripts/reset-running-stack-fixtures.sh` when you need to remove prior runtime test rows.
2. Run `services/app/scripts/seed-running-stack-fixtures.sh` to install the known-good assessor and rebalancer fixtures.
3. Execute `running-instance-smoke-tests` and/or `frontend-running-stack-e2e-tests`.
4. If the stack is shared and should be restored to a neutral state, run `services/app/scripts/reset-running-stack-fixtures.sh` after verification.

## Notes

- The scripts derive Compose and env settings from repo root `.env` and `docker-compose.yml`.
- The scripts delete and recreate only the dedicated test fixture rows listed above.
- Extend this skill whenever a new runtime scenario requires additional seeded state.
