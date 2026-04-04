# Scenario: Backup Import/Export

## Purpose

- Full database backup/export/import preserves application state for disaster recovery and test round-trips.
- Verification skill: `scenario-lifecycle` - keep backup docs, fixtures, and import/export tests aligned when backup semantics change.

## Full database backup

- Full database backups are versioned zip exports.
- Full database backups include `llm_config`.
- Exported full backups currently contain unencrypted LLM API keys to support import round-trips and must be handled as secret material.
- Importing a full backup replaces application data atomically.
- Older full backups without saved LLM settings leave the existing LLM configuration unchanged.

## Knowledge Base backup

- Knowledge Base backups include dossier/extraction tables only.
- Knowledge Base backups exclude `llm_config` and must not carry LLM API keys.
- Importing a Knowledge Base backup replaces existing KB data only.

## Test and fixture expectations

- Synthetic backup fixtures may include fake API keys, but never real secrets.
- E2E coverage should assert the import/export copy for full backup versus Knowledge Base backup.
- Backup fixtures used for current full-backup round-trips should carry `llm_config`; legacy fixtures without `llm_config` remain valid for compatibility tests.
