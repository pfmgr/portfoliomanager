# Scenario: LLM Websearch

## Purpose

- Generate dossier drafts and enrich missing fields using asynchronous LLM websearch jobs.
- Verification skill: `scenario-lifecycle` - keep websearch scenario docs in sync with dossier and extraction workflows.

## Core behavior

- Starts async job to produce dossier draft content.
- Returns job status and results via polling endpoint.
- Missing-data and quality-gate retries can switch from generic allowed domains to a targeted second pass that uses only qualifying primary-source domains discovered in first-pass citations, without falling back to the generic allow-list during that targeted retry.
- When a targeted retry is used, the resulting extraction warnings remain API-visible on extraction responses and are also surfaced on dossier responses via `warnings` so detail views can show retry-scope hints without fetching extraction-only state first.
- Extraction conflict warnings that explain precedence decisions (for example dossier ISIN winning over conflicting LLM output) stay visible in KB dossier/detail responses through the latest extraction warning summary.
- Failures expose `Error ref KB-XXXXXXXX` to UI and logs for correlation.
- Verification skill: `backend-junit-tests` - validate async job lifecycle, payload parsing, and retry behavior in backend tests.

## APIs

- `POST /api/kb/dossiers/websearch` -> returns `{jobId,status}`
- `GET /api/kb/dossiers/websearch/{jobId}` -> returns `{status,result,error}`
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
- Verification skill: `running-instance-smoke-tests` - confirm protected runtime behavior and endpoint availability without changing security posture.

## Code map

- LLM abstraction and providers:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/llm`
- KB APIs and services:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service`
- Verification skill: `backend-junit-tests` - map changed websearch code paths to the relevant async and parsing tests.
