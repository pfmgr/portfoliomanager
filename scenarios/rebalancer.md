# Scenario: Rebalancer

## Purpose

- Rebalancer computes deterministic allocation proposals across portfolio layers and records run history. LLM output is optional and only used for narrative and explanation.
- Verification skill: `scenario-lifecycle` - keep rebalancer docs, tests, and runtime verification aligned when proposal behavior changes.

## Core behavior

- Uses layer target profiles from `services/app/backend/src/main/resources/layer_targets.json`.
- Supports profile switching and optional custom overrides through `layer_target_config`.
- Applies acceptable variance, minimum saving plan size, minimum rebalancing amount, and max saving plans per layer.
- Uses projection blend (`projection_blend_min` / `projection_blend_max`) over 1-120 months.
- Instrument-level saving plan proposals are gated by KB extraction status (`knowledge_base_extractions = COMPLETE`).
- Approved dossier blacklist policy affects saving-plan proposals:
  - instruments excluded from saving plans are proposed at `0 EUR`
  - their reason code is `BLACKLISTED_FROM_SAVING_PLAN_PROPOSALS`
  - runtime narrative and UI should describe them as `Blacklisted from Saving Plan Proposals`
- Verification skill: `backend-junit-tests` - validate instrument proposal redistribution, discard reasons, and gating behavior in deterministic service tests.

## APIs

- `GET /api/rebalancer/**` (analysis/history/reclassifications)
- `GET /api/layer-targets`
- `PUT /api/layer-targets`
- `POST /api/layer-targets/reset`
- Verification skill: `running-instance-smoke-tests` - verify authenticated rebalancer API access and the changed discard behavior against the running stack.

## Data dependencies

- `advisor_runs` for run history.
- `layer_target_config` for active profile and optional custom configuration.
- `knowledge_base_extractions` for instrument-level proposal eligibility.
- `instrument_blacklists` for approved effective exclusion state.
- Verification skill: `backend-junit-tests` - validate schema-driven behavior, repository joins, and saved-run payload shape.

## UI

- `services/app/frontend/src/views/RebalancerView.vue`
- `services/app/frontend/src/views/RebalancerHistoryView.vue`
- `services/app/frontend/src/views/ProfileConfigurationView.vue`
- Verification skill: `frontend-vitest-tests` - confirm discard action badges, reason text, and proposal table rendering.

## Savings plan proposal table semantics

- The table `Rebalancing Proposal (Savings plan amounts, EUR)` compares projections without and with rebalancing for each layer.
- Columns:
  - `Layer`
  - `Current EUR`
  - `Proposed EUR`
  - `Saving Plan Delta EUR`
  - `Current Target %`
  - `Target Total (Rebalanced) %`
  - `Current Target Total Amount EUR`
  - `Target Total Amount (Rebalanced) EUR`
- `Current Target %` and `Current Target Total Amount EUR` are projected from current holdings plus projected contributions using the current saving plan amounts.
- `Target Total (Rebalanced) %` and `Target Total Amount (Rebalanced) EUR` are projected from current holdings plus projected contributions using the proposed saving plan amounts.
- Projection formulas by layer for horizon `H` months:
  - `current_target_total_amount = holdings + current_monthly_amount * H`
  - `rebalanced_target_total_amount = holdings + proposed_monthly_amount * H`
- Verification skill: `frontend-e2e-tests` - verify the table remains stable when discard rows and blacklist reasons appear.

## Code map

- API: `services/app/backend/src/main/java/my/portfoliomanager/app/api/RebalancerController.java`
- Service layer: `services/app/backend/src/main/java/my/portfoliomanager/app/service`
- Layer target config: `services/app/backend/src/main/java/my/portfoliomanager/app/service/LayerTargetConfigService.java`
- Verification skill: `backend-junit-tests` - use service and API integration tests to validate touched classes after blacklist-related changes.

## Edge cases

- Missing KB coverage: no instrument-level proposals, include missing ISINs in response.
- Profile constraints can produce warnings even when tolerance checks pass.
- LLM unavailability must not change deterministic proposal targets.
- Blacklisted saving plans must still appear as discard proposals even when other instrument proposals are gated.
- Verification skill: `frontend-e2e-tests` - exercise discard presentation, gated states, and zero-proposal edge conditions end to end.

## LLM narrative config

- Rebalancer narrative uses action-specific env vars when provided:
  - `LLM_NARRATIVE_PROVIDER`
  - `LLM_NARRATIVE_PROVIDER_API_KEY`
  - `LLM_NARRATIVE_PROVIDER_BASE_URL`
  - `LLM_NARRATIVE_PROVIDER_MODEL`
- Fallback chain:
  - `provider`/`base-url`/`model`: narrative-specific -> global (`LLM_PROVIDER_*`) -> app defaults.
  - `api-key`: narrative-specific -> global key (`LLM_PROVIDER_API_KEY`, `OPENAI_API_KEY`).
- Provider support is currently OpenAI only (`openai`; `none` disables).
- Verification skill: `running-instance-smoke-tests` - confirm runtime narrative-enabled responses remain reachable after rebalancer changes.
