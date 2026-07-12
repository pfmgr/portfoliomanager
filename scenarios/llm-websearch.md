# Scenario: LLM Websearch

## Purpose

- Generate dossier drafts and enrich missing fields using asynchronous LLM websearch jobs.
- Verification skill: `scenario-lifecycle` - keep websearch scenario docs in sync with dossier and extraction workflows.

## Core behavior

- Starts a persistent canonical LLM action to produce dossier draft content; action state, attempts, output, and evidence survive process restarts.
- The canonical API is `/api/kb/llm-actions`: create returns `{actionId,status}`, detail returns status, progress, result, failure, and evidence, and cancellation is explicit.
- `POST /api/kb/dossiers/websearch` and `GET /api/kb/dossiers/websearch/{jobId}` remain legacy websearch adapters. They create/read the corresponding canonical action and must not introduce a separate job lifecycle.
- Actions use only `QUEUED`, `RUNNING`, `WAITING_RETRY`, `REVIEW_REQUIRED`, `COMPLETED`, `FAILED`, or `CANCELED`. Terminal actions never resume; only a new action may be created after a terminal outcome.
- Creation accepts an idempotency key scoped to the caller and action intent. Repeating the same request returns the original action; conflicting reuse is rejected and must not create work twice.
- A worker owns a `RUNNING` action through a renewable lease. Expired leased work is safely resumable from persisted checkpoints/attempt state; a cancellation request prevents any further provider call or apply step and resolves to `CANCELED`.
- Retryable typed failures move to `WAITING_RETRY` with a persisted next-attempt time. Non-retryable typed failures resolve to `FAILED`; exhausted retries do the same. Failures expose `Error ref KB-XXXXXXXX` for UI/log correlation without exposing secrets.
- Missing-data and quality-gate retries can switch from generic allowed domains to a targeted second pass that uses only qualifying primary-source domains discovered in first-pass citations, without falling back to the generic allow-list during that targeted retry.
- When a targeted retry is used, the resulting extraction warnings remain API-visible on extraction responses and are also surfaced on dossier responses via `warnings` so detail views can show retry-scope hints without fetching extraction-only state first.
- Extraction conflict warnings that explain precedence decisions (for example dossier ISIN winning over conflicting LLM output) stay visible in KB dossier/detail responses through the latest extraction warning summary.
- Failures expose `Error ref KB-XXXXXXXX` to UI and logs for correlation.
- Verification skill: `backend-junit-tests` - validate async job lifecycle, payload parsing, and retry behavior in backend tests.

## APIs

- `POST /api/kb/llm-actions`, `GET /api/kb/llm-actions/{actionId}`, and `POST /api/kb/llm-actions/{actionId}/cancel` are the canonical action endpoints.
- `POST /api/kb/dossiers/websearch` -> legacy adapter returning `{jobId,status}` for the canonical action.
- `GET /api/kb/dossiers/websearch/{jobId}` -> legacy adapter returning `{status,result,error}` from that action.
- `result` shape: `{contentMd,displayName,citations,model}`
- KB dossier responses (`GET /api/kb/dossiers/{id}` and `GET /api/kb/dossiers/{isin}` via `latestDossier`) expose latest extraction warning messages in `warnings` for UI visibility.
- Verification skill: `running-instance-smoke-tests` - verify authenticated websearch endpoints and polling behavior when a local stack is running.

## Configuration

- Allowed domains seed file:
  - `services/app/backend/src/main/resources/kb_websearch_allowed_domains.json`
- Provider support: currently only OpenAI is supported (`openai`; `none` disables).
- LLM settings are managed in UI (`LLM Configuration`) and stored in DB (`llm_config`).
- UI editing is enabled only when `LLM_CONFIG_ENCRYPTION_PASSWORD` is set.
- Default mode for websearch is `STANDARD` (inherits provider/base-url/model/API key from standard config).
- If standard API key is missing, websearch in `STANDARD` mode is disabled.
- Optional per-function override is `CUSTOM` mode with its own provider/base-url/model/API key.
- API keys are write-only in the UI: the editor stays collapsed until the user explicitly chooses add/replace, unchanged editors preserve the saved key, and removal is explicit.
- Bulk research parallelism:
  - `max_parallel_bulk_batches` in KB settings (default `2`)
- Verification skill: `backend-junit-tests` - cover config fallback and provider gating in fast backend checks.

## Security and privacy constraints

- Prompt minimization is mandatory for external providers.
- Allowed data: ISINs, instrument names, and required amounts for explanations.
- Disallowed data: user identifiers and secrets.
- If prompt policy is violated, external LLM call must be skipped.
- Evidence is persisted with its source URL/domain and retrieval context. Only configured/qualifying sources may support generated claims; citations must be retained with the result, and unsupported claims produce a typed review/failure outcome rather than invented evidence.
- Provider, transport, rate-limit, prompt-policy, parsing/validation, and source-policy failures are typed. Only explicitly retryable categories are retried.

## Bulk and UI behavior

- A bulk request creates one parent action and independently idempotent child actions. Parent progress is derived from persisted child states; it completes only when all children are terminal, fails when its policy cannot be satisfied, and cancellation cascades to unfinished children.
- The UI reloads action detail/list state from the server after navigation or refresh; it does not infer completion from in-memory polling. It shows persisted status, attempt/retry timing, aggregate/child progress, cancellation, failure reference, and evidence/review state.
- Verification skill: `running-instance-smoke-tests` - confirm protected runtime behavior and endpoint availability without changing security posture.

## Code map

- LLM abstraction and providers:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/llm`
- KB APIs and services:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service`
- Verification skill: `backend-junit-tests` - map changed websearch code paths to the relevant async and parsing tests.
