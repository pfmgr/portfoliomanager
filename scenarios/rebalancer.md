# Scenario: Rebalancer

## Purpose

- Rebalancer computes deterministic allocation proposals across portfolio layers and records run history. LLM output is optional and only used for narrative and explanation.
- Verification skill: `scenario-lifecycle` - keep rebalancer docs, tests, and runtime verification aligned when proposal behavior changes.

## Preconditions

- Standard stack runtime checks use `.env` plus `docker-compose.yml` and must derive frontend/backend URLs from the running `admin_frontend` and `admin_spring` bindings.
- Runtime checks require valid admin credentials for `/auth/token` and a seeded portfolio state with at least one active saving plan.
- Instrument-level proposal tests require KB extraction state to be present for eligible ISINs.
- Verification skills: `running-instance-smoke-tests` for standard-stack API checks, `frontend-running-stack-e2e-tests` for standard-stack browser checks, and `frontend-e2e-tests` for the isolated E2E stack.
- Use `running-stack-fixtures` before standard-stack rebalancer browser checks when the blacklist runtime fixture set is not already seeded.

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
- Selected rebalancer saving-plan proposals can be applied to persisted saving plans from the UI.
- Each proposal row in the apply table supports four immediate decisions: `Apply`, `Ignore`, `Saving plan proposals only`, and `All buy proposals`.
- Applying proposals does not execute depot transactions; it only updates Portfolio Manager saving-plan records.
- Blacklist decisions use the same scope semantics as Knowledge Base blacklist settings and become effective immediately from the table.
- New saving-plan proposals require explicit depot selection before apply, but blacklist and ignore decisions never do.
- If the ISIN has no base instrument, Portfolio Manager materializes one from Knowledge Base metadata.
- If the base instrument exists but is soft-deleted, it is reactivated and becomes effective again.
- New saving plans created from proposals must keep the proposal layer in the effective instrument view.
- If the same ISIN already has multiple active saving plans across depots, apply is blocked instead of auto-distributing the proposal.
- In this conflict case, blacklist decisions remain available because they are instrument-scoped and do not need depot selection.
- Verification skill: `backend-junit-tests` - validate instrument proposal redistribution, discard reasons, and gating behavior in deterministic service tests.

## Minimal fixture set

- One active profile in `layer_target_config`.
- One or more active saving plans across at least one depot.
- One KB-approved instrument eligible for a positive proposal.
- One blacklisted instrument with an existing saving plan so the result contains a zero-amount discard row.
- Optional duplicate active saving plans for the same ISIN across multiple depots for conflict validation.

## Canonical runtime flow

1. Authenticate through `/auth/token` and verify a protected endpoint before starting the scenario-specific run.
2. Read `/api/layer-targets` to capture the active profile and constraints.
3. Trigger a protected rebalancer run and store the returned job identifier.
4. Poll the run endpoint until a terminal result is available.
5. Assert that KB gating, blacklist-driven zero proposals, and visible reason text are present in the result when fixtures demand them.
6. Submit one approval set via `POST /api/sparplans/apply-approvals` and verify the resulting saving-plan state or validation error.

## Canonical assertions

- Protected rebalancer calls succeed only with a valid JWT.
- Blacklisted instruments appear with proposed amount `0 EUR`, reason code `BLACKLISTED_FROM_SAVING_PLAN_PROPOSALS`, and user-visible rationale `Blacklisted from Saving Plan Proposals`.
- Apply without required depot selection is rejected.
- Ambiguous multi-depot matches fail with a clear validation error instead of silently distributing the proposal.
- Newly created saving plans preserve the proposal layer in the effective instrument view.

## APIs

- `POST /api/rebalancer/run`
- `GET /api/rebalancer/run/{jobId}`
- `GET /api/rebalancer/history`
- `POST /api/sparplans/apply-approvals` for persisting selected rebalancer saving-plan proposals.
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

- Route: `/rebalancer`
- `services/app/frontend/src/views/RebalancerView.vue`
- `services/app/frontend/src/components/SavingPlanApprovalsPanel.vue`
- `services/app/frontend/src/views/RebalancerHistoryView.vue`
- `services/app/frontend/src/views/ProfileConfigurationView.vue`
- Stable UI oracles: `Apply Approvals`, `Discard`, `Blacklisted from Saving Plan Proposals`, and the `Rebalancing Proposal (Savings plan amounts, EUR)` table.
- Verification skill: `frontend-vitest-tests` - confirm discard action badges, reason text, and proposal table rendering.

## Test layer mapping

- `frontend-vitest-tests`: cover proposal-table rendering, profile form state, and discard labeling using mocked API payloads.
- `backend-junit-tests`: cover deterministic target computation, run history persistence, gating, and apply-approval validation.
- `running-instance-smoke-tests`: verify auth, protected rebalancer access, one seeded run, and one changed apply or discard runtime path on the standard stack.
- `running-stack-fixtures`: seed and reset the reusable rebalancer runtime fixture rows on the standard stack.
- `frontend-running-stack-e2e-tests`: run real browser verification against the already running standard stack when suitable fixtures exist there.
- `frontend-e2e-tests`: keep isolated-stack user journeys for full browser coverage across frontend and backend.

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
- Applying a new proposal without a depot selection must be blocked.
- Ignored proposals must not change saving plans or blacklists.
- Blacklist decisions must update proposal exclusions immediately with KB-equivalent semantics.
- Applying a proposal for an ISIN without an instrument must create a synthetic effective instrument.
- Applying a proposal for a soft-deleted instrument must reactivate the instrument instead of duplicating it.
- Ambiguous existing saving plans for the same ISIN across multiple depots must fail apply with a clear validation error.
- The layer shown after apply must match the rebalancer proposal layer for new saving plans.
- Verification skill: `frontend-running-stack-e2e-tests` - reuse the running standard stack for browser flows once fixtures cover blacklist and apply paths.
- Verification skill: `frontend-e2e-tests` - exercise discard presentation, gated states, and zero-proposal edge conditions end to end.

## LLM narrative config

- Rebalancer narrative configuration is managed in UI (`LLM Configuration`) and stored in `llm_config`.
- UI editing requires `LLM_CONFIG_ENCRYPTION_PASSWORD`.
- Narrative action defaults to `STANDARD` mode (uses standard provider/base-url/model/API key).
- If narrative is in `STANDARD` mode and no standard API key is configured, narrative generation is disabled.
- Switching from `CUSTOM` back to `STANDARD` clears function-specific narrative API key.
- API keys are write-only in the UI: the editor stays collapsed until the user explicitly chooses add/replace, unchanged editors preserve the saved key, and removal is explicit.
- Provider support is currently OpenAI only (`openai`; `none` disables).
- Full database backups include `llm_config`; exported backup payloads currently carry plaintext API keys for backup/import round-trips.
- Importing a full backup without `llm_config` must preserve the current narrative configuration.
- Verification skill: `running-instance-smoke-tests` - confirm runtime narrative-enabled responses remain reachable after rebalancer changes.
