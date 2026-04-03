# Scenario: Assessor

## Purpose

- Assessor evaluates portfolio quality and proposes saving-plan and one-time actions when gaps exist.
- Verification skill: `scenario-lifecycle` - keep this scenario, its tests, and its verification flow aligned whenever assessor behavior changes.

## Core behavior

- Produces instrument suggestions when KB coverage indicates missing themes, regions, or sub-classes and budget allows action.
- Uses the active rebalancer profile to compute layer target budgets and deltas.
- Applies gate size `max(minimum saving plan size, minimum rebalancing amount)` while processing layer deltas.
- For positive deltas: prefers new candidates via gap detection plus KB weighting; otherwise reallocates to existing plans.
- For negative deltas: applies weighted reductions and disables minimal plans when required to satisfy constraints.
- Approved dossier blacklist policy affects proposals:
  - `ALL_PROPOSALS` excludes the instrument from one-time and saving-plan proposals.
  - `SAVING_PLAN_ONLY` excludes the instrument from saving-plan proposals only.
  - Existing saving plans affected by either blacklist scope are proposed as `discard` with rationale `Blacklisted from Saving Plan Proposals`.
- Approved saving-plan proposals can be applied to persisted saving plans from the assessor UI.
- Each proposal row in the apply table supports four immediate decisions: `Apply`, `Ignore`, `Saving plan proposals only`, and `All buy proposals`.
- Applying proposals does not execute depot transactions; it only updates Portfolio Manager saving-plan records.
- Blacklist decisions use the same scope semantics as Knowledge Base blacklist settings and become effective immediately from the table.
- New saving-plan proposals require the user to choose a depot before apply, but blacklist and ignore decisions never require a depot.
- If the ISIN has no base instrument, Portfolio Manager materializes one from Knowledge Base metadata.
- If the base instrument exists but is soft-deleted, it is reactivated and becomes effective again.
- Newly created saving plans must keep the proposal layer in the effective instrument view after apply.
- Verification skill: `backend-junit-tests` - cover assessor service, suggestion service, and API contract behavior for blacklist filtering, discard output, and layer-budget logic.

## Scoring model

- Penalty-based score (lower is better).
- Inputs include TER, SRI, valuation metrics (P/E, P/B, EV/EBITDA), concentration, single-stock premium, and data quality.
- Layer-sensitive region penalties:
  - Layer 1/2 ETFs use 80%/90% thresholds.
  - Non-ETF layers use 60%/75% thresholds.
  - Layer 3 holdings penalties are reduced.
- Verification skill: `backend-junit-tests` - validate score components and blacklist-driven candidate suppression without relying on UI output.

## APIs

- `GET /api/assessor/**`
- `POST /api/sparplans/apply-approvals` for persisting selected assessor saving-plan proposals.
- Verification skill: `running-instance-smoke-tests` - verify authenticated assessor API access and one changed runtime path against the running stack.

## LLM narrative config

- Narrative configuration is managed in UI (`Profile Configuration -> LLM-Konfiguration`) and stored in `llm_config`.
- UI editing is only available if `LLM_CONFIG_ENCRYPTION_PASSWORD` is configured.
- Narrative defaults to `STANDARD` mode and uses standard provider/base-url/model/API key.
- If narrative is in `STANDARD` mode and no standard API key is set, narrative output is disabled.
- Switching from function-specific (`CUSTOM`) back to `STANDARD` clears the custom narrative API key.
- Provider support is currently OpenAI only (`openai`; `none` disables).
- Full database backups include `llm_config`; exported backup payloads currently carry plaintext API keys for backup/import round-trips.
- Importing a full backup that does not contain `llm_config` must leave the existing LLM configuration unchanged.
- Verification skill: `running-instance-smoke-tests` - confirm narrative-enabled runtime behavior does not break protected API access or changed assessor flows.

## UI

- `services/app/frontend/src/views/AssessorView.vue`
- `services/app/frontend/src/components/SavingPlanApprovalsPanel.vue`
- Verification skill: `frontend-vitest-tests` - validate discard badges, rationale rendering, and one-time vs saving-plan blacklist presentation.

## Code map

- API: `services/app/backend/src/main/java/my/portfoliomanager/app/api/AssessorController.java`
- Services:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorService.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorEngine.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorInstrumentSuggestionService.java`
- Verification skill: `backend-junit-tests` - map each changed service to focused unit or integration coverage.

## Edge cases

- No eligible new candidates for positive layer delta: fallback to existing-plan distribution.
- Negative deltas that violate minimum plan size constraints: controlled plan disable fallback.
- Blacklisted saving-plan instruments must be shown as discard proposals instead of silent omission when an active saving plan exists.
- Applying a new proposal without a depot selection must be blocked.
- Ignored proposals must not change saving plans or blacklists.
- Applying blacklist decisions must update proposal exclusions immediately with KB-equivalent semantics.
- Applying a new proposal for an ISIN without an instrument must create a synthetic effective instrument.
- Applying a proposal for a soft-deleted instrument must reactivate the instrument instead of duplicating it.
- The layer shown after apply must match the assessor proposal layer for new saving plans.
- Verification skill: `frontend-e2e-tests` - cover user-visible blacklist discard flows and gap-handling edge cases across frontend plus backend.
