# Scenario: Saving Plans

## Purpose

- Saving plans can be created, updated, imported, and now also materialize or reactivate base instruments when needed.
- Verification skill: `scenario-lifecycle` - keep direct saving-plan creation, proposal apply flows, and tests aligned.

## Core behavior

- Direct saving-plan creation accepts an ISIN without an existing base instrument when Knowledge Base metadata can seed a synthetic instrument.
- Synthetic instruments must become visible through `instruments_effective` immediately after creation.
- If a matching base instrument exists with `is_deleted = true`, the system reactivates it instead of creating a duplicate record.
- When saving-plan proposals from assessor or rebalancer are applied, new saving plans must keep the proposal layer in the resulting effective instrument.
- Applying proposal approvals updates Portfolio Manager records only; it does not execute real depot transactions.
- The proposal decision table can also ignore a proposal or apply immediate blacklist scopes with the same semantics as Knowledge Base exclusions.
- Blacklist decisions are instrument-scoped, become effective immediately, and do not require depot selection.

## APIs

- `GET /api/sparplans`
- `POST /api/sparplans`
- `PUT /api/sparplans/{id}`
- `POST /api/sparplans/apply-approvals`
- Verification skill: `running-instance-smoke-tests` - verify direct creation, apply approvals, and soft-delete reactivation against the running stack.

## UI

- `services/app/frontend/src/views/SavingsPlansView.vue`
- `services/app/frontend/src/components/SavingPlanApprovalsPanel.vue`
- Verification skill: `frontend-vitest-tests` - validate direct create success handling and apply-approval validation states.

## Edge cases

- Missing Knowledge Base metadata for a brand-new ISIN must fail cleanly without partial persistence.
- Selected proposal rows that need a depot must be blocked until a depot is chosen.
- Ignored proposal rows must be true no-ops.
- Blacklist rows must not create or mutate saving plans unless the row is explicitly applied.
- Reapplying the same proposal must not create duplicate instruments or duplicate saving plans.
- Soft-deleted instruments must be reactivated, not duplicated.
- Knowledge Base blacklist-only edits must not cause extraction freshness to become `OUTDATED`.
- Verification skill: `backend-junit-tests` - cover synthetic creation, reactivation, idempotency, and layer preservation.
