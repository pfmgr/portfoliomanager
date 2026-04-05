# Knowledge Base (KB)

## Prerequisites

KB endpoints require both:
- `KB_ENABLED=true` (default), and
- available LLM action configuration for websearch and extraction (otherwise `/api/kb/**` returns `503`).

Relevant environment variables:
- `KB_ENABLED` -> enables `/api/kb/**` (default: `true`)
- `KB_LLM_ENABLED` -> enables `LlmExtractorService` for extraction runs (default: `false`)
- `LLM_CONFIG_ENCRYPTION_PASSWORD` -> required to edit LLM API settings in UI; default is empty (read-only)

LLM provider/model/base URL/API key are configured in the UI (`LLM Configuration`) and persisted in DB (`llm_config`). API keys are write-only in the UI: editors stay collapsed until users explicitly choose add or replace, unchanged editors preserve the saved key, and removal is explicit. Full database backups include `llm_config`; Knowledge Base backups do not. Exported full backups currently contain LLM API keys in plaintext.

## KB settings (database)

Knowledge Base settings live in the `kb_config` table and are managed via:
- `GET /api/kb/config`
- `PUT /api/kb/config`

LLM runtime settings live in `llm_config` and are managed via:
- `GET /api/llm/config`
- `PUT /api/llm/config`

When importing a full database backup, existing LLM configuration is replaced only if the backup contains `llm_config`. Older backups without `llm_config` leave the current LLM configuration unchanged.

Minimum config fields:
- `enabled` (auto-refresh on/off)
- `refresh_interval_days`
- `auto_approve`
- `apply_extractions_to_overrides`
- `overwrite_existing_overrides`
- `batch_size_instruments`
- `batch_max_input_chars`
- `max_batches_per_run`
- `poll_interval_seconds`
- `max_instruments_per_run`
- `max_retries_per_instrument`
- `base_backoff_seconds`
- `max_backoff_seconds`
- `quality_gate_retry_limit`
- `dossier_max_chars`
- `kb_refresh_min_days_between_runs_per_instrument`
- `run_timeout_minutes`
- `websearch_allowed_domains`

## Workflows

### Bulk research

Create dossiers and extractions for multiple ISINs:
- `POST /api/kb/dossiers/bulk-research`
  - body: `{ "isins": ["DE..."], "autoApprove": false, "applyToOverrides": false }`

If `autoApprove` is true, dossiers and extractions are approved automatically. When
`apply_extractions_to_overrides` is enabled, approved extractions can be applied to
instrument overrides.

### Alternatives

Find alternatives for a base ISIN and generate dossiers/extractions:
- `POST /api/kb/alternatives/{isin}`
  - body: `{ "autoApprove": false }`

Alternatives are stored in `kb_alternatives` with rationale and citations.

### Refresh

Automatic refresh updates stale approved dossiers when enabled in config. Manual
batch runs are available via:
- `POST /api/kb/refresh/batch`
  - body: `{ "limit": 20, "batchSize": 10, "dryRun": true, "scope": { "isins": ["DE..."] } }`
- `POST /api/kb/refresh/{isin}` for a single ISIN

## Approvals

- Approve/reject dossiers: `POST /api/kb/dossiers/{id}/approve|reject`
- Approve/reject/apply extractions: `POST /api/kb/extractions/{id}/approve|reject|apply`

## Sample curl

```bash
# Obtain JWT token through the frontend proxy
# Use https://localhost:8443 when the compose wizard enables frontend TLS.
FRONTEND_BASE_URL="http://localhost:8080"
TOKEN=$(curl -s -X POST "${FRONTEND_BASE_URL}/auth/token" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | \
  python3 -c "import sys, json; print(json.load(sys.stdin).get('token',''))")

# Read KB config
curl -H "Authorization: Bearer $TOKEN" "${FRONTEND_BASE_URL}/api/kb/config"

# Update KB config (partial update is allowed)
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "enabled": true,
  "auto_approve": false
}' "${FRONTEND_BASE_URL}/api/kb/config"

# Bulk research
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "isins": ["DE0000000001"],
  "autoApprove": false,
  "applyToOverrides": false
}' "${FRONTEND_BASE_URL}/api/kb/dossiers/bulk-research"

# Find alternatives
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "autoApprove": false
}' "${FRONTEND_BASE_URL}/api/kb/alternatives/DE0000000001"

# Dry-run refresh batch
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "limit": 2,
  "batchSize": 1,
  "dryRun": true,
  "scope": { "isins": ["DE0000000001", "DE0000000002"] }
}' "${FRONTEND_BASE_URL}/api/kb/refresh/batch"
```
