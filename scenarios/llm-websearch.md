# Scenario: LLM Websearch

## Purpose

Generate dossier drafts and enrich missing fields using asynchronous LLM websearch jobs.

## Core behavior

- Starts async job to produce dossier draft content.
- Returns job status/results via polling endpoint.
- Missing-data patch retries websearch with primary sources and issuer/ETF domains discovered in first-pass citations.
- Failures expose `Error ref KB-XXXXXXXX` to UI and logs for correlation.

## APIs

- `POST /api/kb/dossiers/websearch` -> returns `{jobId,status}`
- `GET /api/kb/dossiers/websearch/{jobId}` -> returns `{status,result,error}`
- `result` shape: `{contentMd,displayName,citations,model}`

## Configuration

- Allowed domains seed file:
  - `services/app/backend/src/main/resources/kb_websearch_allowed_domains.json`
- OpenAI timeouts:
  - `app.llm.openai.connect-timeout-seconds`
  - `app.llm.openai.read-timeout-seconds`
- Bulk research parallelism:
  - `max_parallel_bulk_batches` in KB settings (default `2`)

## Security and privacy constraints

- Prompt minimization is mandatory for external providers.
- Allowed data: ISINs, instrument names, and required amounts for explanations.
- Disallowed data: user identifiers and secrets.
- If prompt policy is violated, external LLM call must be skipped.

## Code map

- LLM abstraction and providers:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/llm`
- KB APIs/services:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service`
