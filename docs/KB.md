# Knowledge Base (KB)

## Prerequisites

KB endpoints require both:
- `KB_ENABLED=true` (default), and
- an enabled LLM provider (otherwise `/api/kb/**` returns `503`).

Relevant environment variables (see `services/app/backend/src/main/resources/application.yaml`):
- `KB_ENABLED` -> enables `/api/kb/**` (default: `true`)
- `KB_LLM_ENABLED` -> enables `LlmExtractorService` for extraction runs (default: `false`)
- `LLM_PROVIDER` -> set to `openai` to enable the LLM client (default: `none`)
- `LLM_PROVIDER_API_KEY` (or `OPENAI_API_KEY` fallback)
- `LLM_PROVIDER_MODEL` (must support `web_search` for dossier research)
- `LLM_PROVIDER_BASE_URL` (optional; default OpenAI API base URL)

## KB settings (database)

Knowledge Base settings live in the `kb_config` table and are managed via:
- `GET /api/kb/config`
- `PUT /api/kb/config`

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
# Read KB config
curl -u admin:admin "http://localhost:8089/api/kb/config"

# Update KB config (partial update is allowed)
curl -u admin:admin -H "Content-Type: application/json" -d '{
  "enabled": true,
  "auto_approve": false
}' "http://localhost:8089/api/kb/config"

# Bulk research
curl -u admin:admin -H "Content-Type: application/json" -d '{
  "isins": ["DE0000000001"],
  "autoApprove": false,
  "applyToOverrides": false
}' "http://localhost:8089/api/kb/dossiers/bulk-research"

# Find alternatives
curl -u admin:admin -H "Content-Type: application/json" -d '{
  "autoApprove": false
}' "http://localhost:8089/api/kb/alternatives/DE0000000001"

# Dry-run refresh batch
curl -u admin:admin -H "Content-Type: application/json" -d '{
  "limit": 2,
  "batchSize": 1,
  "dryRun": true,
  "scope": { "isins": ["DE0000000001", "DE0000000002"] }
}' "http://localhost:8089/api/kb/refresh/batch"
```
