# Scenario Template

## Purpose

- Describe the business outcome in one or two lines.
- Reference `scenario-lifecycle` when the scenario drives implementation and verification.

## Preconditions

- Required feature flags, credentials, and running services.
- Runtime assumptions for local Docker stack versus isolated E2E stack.
- Prefer skill references such as `running-instance-smoke-tests`, `frontend-running-stack-e2e-tests`, and `frontend-e2e-tests` over direct script paths.

## Minimal fixture set

- Smallest reproducible data shape needed for runtime verification.
- Call out required depots, ISINs, KB states, blacklist states, and saving-plan states.

## Core behavior

- Happy-path behavior that must remain stable.
- Add the primary backend verification skill when service or API logic changes.

## Canonical runtime flow

- Describe the API or UI flow as ordered steps.
- Include polling rules, expected status transitions, and success criteria.

## Canonical assertions

- List the response fields, UI markers, and side effects that act as test oracles.
- Prefer stable labels, reason codes, and persisted outcomes over incidental rendering.

## APIs

- List canonical endpoints instead of broad wildcard groups when possible.
- Include the runtime verification skill for protected endpoint checks.

## Data dependencies

- Name the tables or persisted state that make the scenario reproducible.

## UI

- Name the route, primary controls, and stable user-visible success/error markers.
- Include the frontend verification skills used for stubbed and full-stack coverage.

## Test layer mapping

- `frontend-vitest-tests`: UI state and rendering with mocked API responses.
- `backend-junit-tests`: service and API contract coverage.
- `running-instance-smoke-tests`: auth, protected endpoints, and one changed runtime path on the standard stack.
- `frontend-running-stack-e2e-tests`: browser verification against the already running standard stack.
- `frontend-e2e-tests`: isolated end-to-end coverage against the dedicated E2E stack.

## Edge cases

- Only include edge cases that materially change runtime behavior or persistence.

## Code map

- Map the scenario to the smallest useful backend and frontend entry points.
