# Scenario: Knowledge Base Extraction

## Purpose

KB extraction enriches instruments/dossiers and feeds downstream classification, rebalancer, and assessor workflows.

## Core behavior

- On import/reclassification, KB extraction data by ISIN has precedence over ruleset output.
- Rules are used when no KB entry exists.
- Extraction lifecycle (run/approve/apply) syncs data into `knowledge_base_extractions`.
- Instrument-level rebalancer proposals are enabled only when latest extraction status is `COMPLETE`.
- Missing-data patch can be triggered for a dossier and fills only absent fields while preserving existing structure.

## APIs

- `POST /api/kb/dossiers/{isin}/missing-data`
- Additional KB extraction endpoints under `/api/kb/**`.

## Data dependencies

- `knowledge_base_extractions`
- `instrument_dossier_extractions`
- `instrument_dossiers`
- `instrument_facts`
- `kb_runs`
- `kb_config`
- `kb_alternatives`

## Config and gating

- KB endpoints require `KB_ENABLED=true` and a configured/enabled LLM provider.
- If LLM is not available, `/api/kb/**` returns `503`.
- Test profile disables KB refresh scheduler via `app.kb.refresh-scheduler-enabled=false`.

## Code map

- KB APIs/services: `services/app/backend/src/main/java/my/portfoliomanager/app/api` and `services/app/backend/src/main/java/my/portfoliomanager/app/service`
- Liquibase schema: `services/app/backend/src/main/resources/db/changelog/db.changelog-master.yaml`

## Edge cases

- Unknown ISIN dossiers are allowed and do not require an existing instrument row.
- Backup/import logic must include all KB tables in truncate/import/sequence-reset order after schema changes.
