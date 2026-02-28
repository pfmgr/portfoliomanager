# Scenario: Assessor

## Purpose

Assessor evaluates portfolio quality and proposes saving-plan and one-time actions when gaps exist.

## Core behavior

- Produces instrument suggestions when KB coverage indicates missing themes/regions/sub-classes and budget allows action.
- Uses the active rebalancer profile to compute layer target budgets and deltas.
- Applies gate size `max(minimum saving plan size, minimum rebalancing amount)` while processing layer deltas.
- For positive deltas: prefers new candidates via gap detection + KB weighting; otherwise reallocates to existing plans.
- For negative deltas: applies weighted reductions; disables minimal plans when required to satisfy constraints.

## Scoring model

- Penalty-based score (lower is better).
- Inputs include TER, SRI, valuation metrics (P/E, P/B, EV/EBITDA), concentration, single-stock premium, and data quality.
- Layer-sensitive region penalties:
  - Layer 1/2 ETFs use 80%/90% thresholds.
  - Non-ETF layers use 60%/75% thresholds.
  - Layer 3 holdings penalties are reduced.

## APIs

- `GET /api/assessor/**`

## UI

- `services/app/frontend/src/views/AssessorView.vue`

## Code map

- API: `services/app/backend/src/main/java/my/portfoliomanager/app/api/AssessorController.java`
- Services:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorService.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorEngine.java`

## Edge cases

- No eligible new candidates for positive layer delta: fallback to existing-plan distribution.
- Negative deltas that violate minimum plan size constraints: controlled plan disable fallback.
