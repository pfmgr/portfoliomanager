# Scenario: Rebalancer

## Purpose

Rebalancer computes deterministic allocation proposals across portfolio layers and records run history. LLM output is optional and only used for narrative/explanation.

## Core behavior

- Uses layer target profiles from `services/app/backend/src/main/resources/layer_targets.json`.
- Supports profile switching and optional custom overrides through `layer_target_config`.
- Applies acceptable variance, minimum saving plan size, minimum rebalancing amount, and max saving plans per layer.
- Uses projection blend (`projection_blend_min` / `projection_blend_max`) over 1-120 months.
- Instrument-level saving plan proposals are gated by KB extraction status (`knowledge_base_extractions = COMPLETE`).

## APIs

- `GET /api/rebalancer/**` (analysis/history/reclassifications)
- `GET /api/layer-targets`
- `PUT /api/layer-targets`
- `POST /api/layer-targets/reset`

## Data dependencies

- `advisor_runs` for run history.
- `layer_target_config` for active profile and optional custom configuration.
- `knowledge_base_extractions` for instrument-level proposal eligibility.

## UI

- `services/app/frontend/src/views/RebalancerView.vue`
- `services/app/frontend/src/views/RebalancerHistoryView.vue`
- `services/app/frontend/src/views/ProfileConfigurationView.vue`

## Code map

- API: `services/app/backend/src/main/java/my/portfoliomanager/app/api/RebalancerController.java`
- Service layer: `services/app/backend/src/main/java/my/portfoliomanager/app/service`
- Layer target config: `services/app/backend/src/main/java/my/portfoliomanager/app/service/LayerTargetConfigService.java`

## Edge cases

- Missing KB coverage: no instrument-level proposals, include missing ISINs in response.
- Profile constraints can produce warnings even when tolerance checks pass.
- LLM unavailability must not change deterministic proposal targets.

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
