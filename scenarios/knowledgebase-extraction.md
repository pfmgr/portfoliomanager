# Scenario: Knowledge Base Extraction

## Purpose

- KB extraction enriches instruments and dossiers and feeds downstream classification, rebalancer, and assessor workflows.
- Verification skill: `scenario-lifecycle` - keep Knowledge Base extraction docs, tests, and downstream scenarios aligned after feature changes.

## Core behavior

- On import and reclassification, KB extraction data by ISIN has precedence over ruleset output.
- Rules are used when no KB entry exists.
- Extraction lifecycle (run, approve, apply) syncs data into `knowledge_base_extractions`.
- Instrument-level rebalancer proposals are enabled only when latest extraction status is `COMPLETE`.
- Missing-data patch can be triggered for a dossier and fills only absent fields while preserving existing structure.
- Approved dossier blacklist policy is configured in KB dossier detail and becomes effective only after dossier approval or auto-approval.
- Verification skill: `backend-junit-tests` - validate extraction lifecycle, blacklist activation timing, and downstream state synchronization.

## APIs

- `POST /api/kb/dossiers/{id}/extract`
- `POST /api/kb/dossiers/{id}/complete-missing-metrics`
- Additional KB extraction endpoints under `/api/kb/**`.
- Verification skill: `knowledge-base-dossier-checks` - verify dossier detail, approval flow, and list filters against the running KB UI and API.

## Data dependencies

- `knowledge_base_extractions`
- `instrument_dossier_extractions`
- `instrument_dossiers`
- `instrument_blacklists`
- `instrument_facts`
- `kb_runs`
- `kb_config`
- `kb_alternatives`
- Verification skill: `backend-junit-tests` - cover migration, repository query, and backup/import implications of the KB schema.

## Config and gating

- KB endpoints require `KB_ENABLED=true` and a configured and enabled LLM provider.
- LLM settings are configured in UI (`LLM Configuration`) and persisted in `llm_config`.
- UI editing requires `LLM_CONFIG_ENCRYPTION_PASSWORD` to be set.
- Extraction mode defaults to `STANDARD` and uses the standard API key unless switched to `CUSTOM`.
- If extraction stays in `STANDARD` mode and no standard API key is set, extraction is disabled.
- API keys are write-only in the UI: the editor stays collapsed until the user explicitly chooses add/replace, unchanged editors preserve the saved key, and removal is explicit.
- Provider support is currently OpenAI only (`openai`; `none` disables).
- LLM-enabled KB endpoints return `503` when the required action config is unavailable:
  - websearch endpoints require websearch action config
  - extraction endpoints require extraction action config
- Test profile disables KB refresh scheduler via `app.kb.refresh-scheduler-enabled=false`.
- Verification skill: `running-instance-smoke-tests` - confirm protected KB endpoints, approval actions, and changed runtime behavior stay available.

## Dossier overview and blacklist management

- Dossier detail exposes requested vs effective blacklist scope.
- Dossier filter settings are collapsible in the UI and start hidden until the user expands them.
- When dossier filters are collapsed, the UI summarizes which filters are currently active.
- Dossier list supports filtering and sorting by:
  - dossier status
  - approval status (`APPROVED` vs `NOT_APPROVED`)
  - extraction status
  - extraction freshness
  - blacklist status
- Pending blacklist changes are visible in the UI but do not count as effective until approval or auto-approval.
- Verification skill: `knowledge-base-dossier-checks` - exercise dossier list sort/filter behavior and pending-versus-effective state transitions.

## Code map

- KB APIs and services: `services/app/backend/src/main/java/my/portfoliomanager/app/api` and `services/app/backend/src/main/java/my/portfoliomanager/app/service`
- Liquibase schema: `services/app/backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- Verification skill: `backend-junit-tests` - map each schema and service change to integration coverage before running broader suites.

## Edge cases

- Unknown ISIN dossiers are allowed and do not require an existing instrument row.
- Large valuation values (for example trillion-scale `market_cap` or `enterprise_value`) must be persisted in `instrument_facts.fact_value_num` without numeric overflow during approve/apply flows.
- Backup and import logic must include all KB tables in truncate, import, and sequence-reset order after schema changes.
- Knowledge Base backup/export scope excludes `llm_config`; KB import must not overwrite existing runtime LLM configuration.
- Rejected dossier blacklist changes must not become effective.
- Verification skill: `knowledge-base-dossier-checks` - verify pending changes are cleared or kept inactive when approval is not granted.
