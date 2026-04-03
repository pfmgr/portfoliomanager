# Scenario: LLM Websearch

## Purpose

- Generate dossier drafts and enrich missing fields using asynchronous LLM websearch jobs.
- Verification skill: `scenario-lifecycle` - keep websearch scenario docs in sync with dossier and extraction workflows.

## Core behavior

- Starts async job to produce dossier draft content.
- Returns job status and results via polling endpoint.
- Missing-data patch retries websearch with primary sources and issuer or ETF domains discovered in first-pass citations.
- Failures expose `Error ref KB-XXXXXXXX` to UI and logs for correlation.
- Verification skill: `backend-junit-tests` - validate async job lifecycle, payload parsing, and retry behavior in backend tests.

## APIs

- `POST /api/kb/dossiers/websearch` -> returns `{jobId,status}`
- `GET /api/kb/dossiers/websearch/{jobId}` -> returns `{status,result,error}`
- `result` shape: `{contentMd,displayName,citations,model}`
- Verification skill: `running-instance-smoke-tests` - verify authenticated websearch endpoints and polling behavior when a local stack is running.

## Configuration

- Allowed domains seed file:
  - `services/app/backend/src/main/resources/kb_websearch_allowed_domains.json`
- Provider support: currently only OpenAI is supported (`openai`; `none` disables).
- LLM settings are managed in UI (`Profile Configuration -> LLM-Konfiguration`) and stored in DB (`llm_config`).
- UI editing is enabled only when `LLM_CONFIG_ENCRYPTION_PASSWORD` is set.
- Default mode for websearch is `STANDARD` (inherits provider/base-url/model/API key from standard config).
- If standard API key is missing, websearch in `STANDARD` mode is disabled.
- Optional per-function override is `CUSTOM` mode with its own provider/base-url/model/API key.
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
