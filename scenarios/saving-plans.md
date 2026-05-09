# Scenario: Saving Plans

## Purpose

- Saving plans can be created, updated, imported, and now also materialize or reactivate base instruments when needed.
- Verification skill: `scenario-lifecycle` - keep direct saving-plan creation, proposal apply flows, and tests aligned.

## Preconditions

- Standard stack runtime checks use `.env` plus `docker-compose.yml` and require a reachable frontend plus a valid admin login; backend access is internal by default.
- Direct create flows need at least one depot and, for synthetic-instrument creation, KB metadata for the requested ISIN.
- Long-metadata regression checks require the running stack to have applied the `021-descriptive-fields-text` migration so descriptive fields are `TEXT` rather than old `VARCHAR` limits.
- Apply-approval checks depend on seeded assessor or rebalancer proposals and at least one available depot.
- Verification skills: `running-instance-smoke-tests` for standard-stack API checks, `frontend-running-stack-e2e-tests` for standard-stack browser checks, and `frontend-e2e-tests` for the isolated E2E stack.

## Minimal fixture set

- One depot that can receive newly created saving plans.
- One KB-backed ISIN without a base instrument for synthetic creation.
- One KB-backed ISIN whose extracted descriptive fields exceed the old limits: name >255 chars, `instrument_type`/`asset_class`/`sub_class` >128 chars, and `layer_notes` >512 chars.
- One soft-deleted base instrument for reactivation tests.
- Four short base instruments for API validation controls: invalid `dayOfMonth` low/high, overlong `frequency`, and excess amount precision.
- One pending proposal that requires depot selection and one blacklist-only proposal that does not.

## Core behavior

- Direct saving-plan creation accepts an ISIN without an existing base instrument when Knowledge Base metadata can seed a synthetic instrument.
- Synthetic instruments must become visible through `instruments_effective` immediately after creation.
- Synthetic instrument materialization must tolerate long descriptive Knowledge Base fields such as instrument name, classification labels, and layer notes.
- If a matching base instrument exists with `is_deleted = true`, the system reactivates it instead of creating a duplicate record.
- When saving-plan proposals from assessor or rebalancer are applied, new saving plans must keep the proposal layer in the resulting effective instrument.
- Applying proposal approvals updates Portfolio Manager records only; it does not execute real depot transactions.
- The proposal decision table can also ignore a proposal or apply immediate blacklist scopes with the same semantics as Knowledge Base exclusions.
- Blacklist decisions are instrument-scoped, become effective immediately, and do not require depot selection.

## Canonical runtime flow

1. Authenticate through `/auth/token` and verify protected access to `/api/sparplans`.
2. Create or update a saving plan directly for a seeded ISIN and confirm the persisted response.
3. If the ISIN has no active base instrument, verify that the effective instrument becomes visible after creation.
4. Create a saving plan for a KB-backed ISIN with long descriptive metadata and confirm the response and read-back do not fail with `SQLState 22001`.
5. Apply a seeded proposal approval set through `POST /api/sparplans/apply-approvals`.
6. Re-read saving plans or effective instruments and verify materialization, reactivation, or blacklist-only no-op behavior.

## Canonical assertions

- Direct create requests persist exactly one saving plan row and do not duplicate instruments on retries.
- Long KB metadata is preserved beyond the former `VARCHAR` limits on the materialized base instrument.
- Invalid direct-create API values are rejected with `400` before persistence.
- Missing depot selection blocks only rows that require persistence.
- Blacklist-only decisions update exclusion state without creating or mutating saving plans.
- Soft-deleted instruments are reactivated instead of duplicated.

## APIs

- `GET /api/sparplans`
- `POST /api/sparplans`
- `PUT /api/sparplans/{id}`
- `POST /api/sparplans/apply-approvals`
- Verification skill: `running-instance-smoke-tests` - verify direct creation, apply approvals, and soft-delete reactivation against the running stack.

## Data dependencies

- `saving_plans`
- `instruments`
- `instruments_effective`
- `instrument_blacklists`
- `knowledge_base_extractions`
- `instrument_dossiers`
- `instrument_dossier_extractions`

## UI

- Route: `/sparplans`
- `services/app/frontend/src/views/SavingsPlansView.vue`
- `services/app/frontend/src/components/SavingPlanApprovalsPanel.vue`
- Stable UI oracles: save success state for direct creation, apply-approval validation messages, and visible reactivated or newly materialized instruments after refresh.
- Verification skill: `frontend-vitest-tests` - validate direct create success handling and apply-approval validation states.

## Test layer mapping

- `frontend-vitest-tests`: validate form validation, success feedback, and approval-dialog edge states with mocked API data.
- `backend-junit-tests`: validate synthetic creation, reactivation, idempotency, and layer preservation.
- `running-instance-smoke-tests`: verify login, protected saving-plan access, one direct create, and one apply-approval runtime path on the standard stack. Set `SMOKE_INCLUDE_SAVING_PLAN_LONG_METADATA=true` to include `services/app/scripts/run-saving-plan-long-metadata-smoke.sh`.
- `frontend-running-stack-e2e-tests`: verify real browser create and apply flows against the running standard stack when fixtures are already seeded.
- `frontend-e2e-tests`: cover a small number of full UI flows that exercise create and apply behavior against the isolated E2E stack.

## Edge cases

- Missing Knowledge Base metadata for a brand-new ISIN must fail cleanly without partial persistence.
- Long descriptive Knowledge Base fields must not fail saving-plan creation because of base-instrument column length constraints.
- Invalid direct-create values such as `dayOfMonth` outside `1..31`, overlong `frequency`, or excess `amountEur` precision must be rejected before persistence.
- Selected proposal rows that need a depot must be blocked until a depot is chosen.
- Ignored proposal rows must be true no-ops.
- Blacklist rows must not create or mutate saving plans unless the row is explicitly applied.
- Reapplying the same proposal must not create duplicate instruments or duplicate saving plans.
- Soft-deleted instruments must be reactivated, not duplicated.
- Knowledge Base blacklist-only edits must not cause extraction freshness to become `OUTDATED`.
- Verification skill: `backend-junit-tests` - cover synthetic creation, reactivation, idempotency, and layer preservation.
