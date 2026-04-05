# Scenario: Assessor

## Purpose

- Assessor evaluates portfolio quality and proposes saving-plan and one-time actions when gaps exist.
- Verification skill: `scenario-lifecycle` - keep this scenario, its tests, and its verification flow aligned whenever assessor behavior changes.

## Preconditions

- Standard stack runtime checks use `.env` plus `docker-compose.yml` with reachable `admin_frontend`; `admin_spring` stays internal unless a debug override exposes it.
- Runtime auth checks require valid `ADMIN_USER` and `ADMIN_PASS` credentials from `.env`.
- Assessor proposal generation assumes KB is enabled and relevant instrument metadata is already available when gap detection or blacklist handling is under test.
- Verification skills: `running-instance-smoke-tests` for standard-stack API checks, `frontend-running-stack-e2e-tests` for standard-stack browser checks, and `frontend-e2e-tests` for the isolated E2E stack.
- Use `running-stack-fixtures` before standard-stack assessor browser checks when the required discard fixture data is not already present.

## Minimal fixture set

- One active rebalancer profile with deterministic layer targets.
- One depot available for apply flows.
- One existing active saving plan that can become a `discard` proposal after blacklist approval.
- One KB-backed candidate ISIN that can be suggested as a new saving plan.
- Optional soft-deleted base instrument for reactivation checks.

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

## Canonical runtime flow

1. Authenticate against `/auth/token` with `.env` credentials and retain the JWT for protected calls.
2. Load assessor-relevant configuration from `/api/layer-targets` before starting the run.
3. Start an assessor run via the protected assessor endpoint and capture the returned job identifier.
4. Poll the job endpoint until the run reaches a terminal state.
5. Assert that blacklist-affected existing saving plans are returned as `discard` proposals instead of disappearing from the result.
6. Apply one approval decision via `POST /api/sparplans/apply-approvals` and verify the persisted change through a follow-up protected API call or visible UI state.

## Canonical assertions

- Run responses expose a terminal job status and a non-empty `result` payload for seeded happy paths.
- Existing blacklisted saving plans carry action `discard` and rationale `Blacklisted from Saving Plan Proposals`.
- New proposals that require persistence cannot be applied without a depot selection.
- Successful apply responses report created, updated, ignored, and blacklist counters consistently with the submitted decisions.
- Reactivated or newly materialized instruments remain visible through the effective saving-plan and instrument views after apply.

## Scoring model

- Penalty-based score (lower is better).
- Inputs include TER, SRI, valuation metrics (P/E, P/B, EV/EBITDA), concentration, single-stock premium, and data quality.
- Layer-sensitive region penalties:
  - Layer 1/2 ETFs use 80%/90% thresholds.
  - Non-ETF layers use 60%/75% thresholds.
  - Layer 3 holdings penalties are reduced.
- Verification skill: `backend-junit-tests` - validate score components and blacklist-driven candidate suppression without relying on UI output.

## APIs

- `POST /api/assessor/run`
- `GET /api/assessor/run/{jobId}`
- `POST /api/sparplans/apply-approvals` for persisting selected assessor saving-plan proposals.
- Verification skill: `running-instance-smoke-tests` - verify authenticated assessor API access and one changed runtime path against the running stack.

## LLM narrative config

- Narrative configuration is managed in UI (`LLM Configuration`) and stored in `llm_config`.
- UI editing is only available if `LLM_CONFIG_ENCRYPTION_PASSWORD` is configured.
- Narrative defaults to `STANDARD` mode and uses standard provider/base-url/model/API key.
- If narrative is in `STANDARD` mode and no standard API key is set, narrative output is disabled.
- Switching from function-specific (`CUSTOM`) back to `STANDARD` clears the custom narrative API key.
- API keys are write-only in the UI: the editor stays collapsed until the user explicitly chooses add/replace, unchanged editors preserve the saved key, and removal is explicit.
- Provider support is currently OpenAI only (`openai`; `none` disables).
- Full database backups include `llm_config`; exported backup payloads currently carry plaintext API keys for backup/import round-trips.
- Importing a full backup that does not contain `llm_config` must leave the existing LLM configuration unchanged.
- Verification skill: `running-instance-smoke-tests` - confirm narrative-enabled runtime behavior does not break protected API access or changed assessor flows.

## UI

- Route: `/assessor`
- `services/app/frontend/src/views/AssessorView.vue`
- `services/app/frontend/src/components/SavingPlanApprovalsPanel.vue`
- Stable UI oracles: `Run Assessment`, `Apply Approvals`, `Discard`, `Blacklisted from Saving Plan Proposals`, and the depot selector shown for apply decisions.
- Verification skill: `frontend-vitest-tests` - validate discard badges, rationale rendering, and one-time vs saving-plan blacklist presentation.

## Test layer mapping

- `frontend-vitest-tests`: validate table rendering, apply-dialog validation states, and blacklist labels with mocked API payloads.
- `backend-junit-tests`: cover layer-budget math, candidate filtering, discard generation, and apply-approval persistence rules.
- `running-instance-smoke-tests`: validate login, protected assessor access, one seeded assessor run, and one apply-approval path on the standard stack from `.env` plus `docker-compose.yml`.
- `running-stack-fixtures`: seed and reset the reusable assessor runtime fixture rows on the standard stack.
- `frontend-running-stack-e2e-tests`: run real browser flows against the already running standard stack when seeded runtime data is available.
- `frontend-e2e-tests`: keep isolated-stack coverage focused on end-to-end user-visible assessor flows.

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
- Verification skill: `frontend-running-stack-e2e-tests` - reuse the running standard stack for browser checks when the scenario fixture set already exists there.
- Verification skill: `frontend-e2e-tests` - cover user-visible blacklist discard flows and gap-handling edge cases across frontend plus backend.
