# AGENTS.MD — Portfolio Stack (Codex Guide)

This repository contains a small, self-hosted portfolio tracker for **Trade Republic (TR) PDF** and **Deka CSV** exports.
It imports positions into PostgreSQL and manages savings plans via the **Spring Boot Admin API + Vue 3 UI**, then lets you classify instruments (type/asset class/layer) using a **rules engine**. The Admin UI authenticates via JWT login only.
The current stack is a **Spring Boot Admin API** plus a **Vue 3 UI** under `services/app/` for imports, reclassification rulesets, reclassifications, and advisor summaries.

The intent of this document is to make it straightforward for Codex (and other agents) to implement changes safely.

---

## 1) What the system does (end-to-end)

**Inputs (files)**
- Spring UI uploads:
  - TR account statement / holdings export as **PDF**
  - Deka depot holdings as **CSV**

**Depot statement import (Spring API)**
- Parses uploads into `Position` objects:
  - `isin`, `name`, `shares`, `value_eur`, `as_of_date`, plus source metadata
- Imports/updates instruments (dedupe + mark missing per depot as deleted) and applies rules, then creates:
  - `snapshots` rows per (depot, as_of_date, source, file_hash)
  - `snapshot_positions` per ISIN
- Sets `depots.active_snapshot_id` to the newest imported snapshot per depot
- Skips already imported files by file hash (unless forced).

**Admin UI (Spring Boot + Vue)**
- Web UI with **JWT login** for:
  - importing depot statements (TR PDF / Deka CSV) to update instruments + snapshots
  - exporting snapshots as CSV (filter by depot/date/source/snapshot_id)
  - managing reclassification rulesets and applying reclassifications
  - importing/exporting overrides as CSV (skips unknown instruments)
  - creating, editing, deleting savings plans
  - running advisor summary analysis
- Depot statement imports can trigger rule application automatically.

**Database (PostgreSQL)**
- Stores master data, savings plans (`sparplans` table), file import history, snapshots, and snapshot positions.
- Layer concept:
  - Layers `1..4` = your “Core-Satellite” tiers
  - Layer `5` = **unclassified** (default for newly discovered instruments)

---

## 2) Services & composition

Services in `docker-compose.yml`:
- `db_portfolio` (Postgres 16.x; built from `postgres/Dockerfile` to raise `max_connections`)
- `admin_spring` (Spring Boot; reclassification rulesets + advisor API; JWT issuance)
- `admin_frontend` (Vue 3 + Vite build served by Nginx; proxies /api to admin_spring)
- `metabase` + `db_metabase` (BI dashboard; included in `docker-compose.yml`). If you don't need it, start only the core services or remove these services.

Ports / binds:
- Services should bind only to LAN interfaces (e.g. `192.168.x.x`) or be fronted by a LAN-only reverse proxy.
- JWT auth is used for the Admin UI; the frontend does not use Basic Auth.
- Note: `metabase` is currently exposed via `3000:3000` (all interfaces). Restrict it if needed (e.g. `127.0.0.1:3000:3000`).

---

## 3) Repository layout (current)

- `docker-compose.yml`
- `.env` (local; not committed)
- `services/app/backend/` (Spring Boot API + Liquibase + rules engine)
  - `services/app/backend/src/main/resources/application.yaml` (runtime config; env var mapping)
  - `services/app/backend/src/main/resources/db/changelog/db.changelog-master.yaml` (Liquibase schema/migrations)
  - `services/app/backend/src/main/resources/layer_targets.json` (advisor target weights + variance tolerance defaults)
  - `services/app/backend/src/main/resources/default_classification_ruleset.json` (default reclassification ruleset loaded on first start)
- `services/app/frontend/` (Vue 3 + Vite UI served via Nginx)
  - `services/app/frontend/src/` (UI code)
  - `services/app/frontend/tests/unit/` (Vitest unit tests)
  - `services/app/frontend/tests/e2e/` (Playwright e2e tests)
  - `services/app/frontend/scripts/run-e2e.sh` (preferred e2e runner)

If your repo differs, **Codex should search by symbol/function name** rather than relying on paths.

---

## 4) Database schema (conceptual)

Minimum tables:
- `depots(depot_id, depot_code, name, provider, active_snapshot_id, ...)`
- `instruments(isin PK, name NOT NULL, depot_code REFERENCES depots.depot_code DEFAULT 'tr', instrument_type, asset_class, sub_class, layer, is_deleted, ...)`
- `sparplans(...)` and `sparplans_history(...)`
- `import_files(...)` (dedupe file imports by hash)
- `snapshots(...)`
- `snapshot_positions(snapshot_id, isin, shares, value_eur, ...)`
- `advisor_runs(...)` (advisor history)
- `instrument_classifications`, `instrument_overrides`

Key invariants / constraints:
- `instruments.name` is **NOT NULL**
- `instruments.layer` is constrained to `1..5` (with default `5`)
- `instruments.depot_code` is **NOT NULL**, defaults to `tr`, and references `depots.depot_code`
- `instruments.is_deleted` defaults to `false` (soft delete flag)
- `import_files` should have a unique constraint on `(depot_code, file_hash)` so re-import is idempotent

---

## 5) Depot statement import behavior and how to extend it

### Import flow
- Spring Boot parses uploads with the shared parsers.
- Instruments are upserted (deduped) and missing instruments for the depot are marked deleted; rules are applied before snapshot rows are written.
- When applying reclassification on import, Knowledge Base extraction data (matched by ISIN) takes precedence; rules apply only when no KB entry exists.
- File hash dedupe uses `import_files`; "force reimport" bypasses it.

### Add a new broker/export format
1. Create a parser under `services/app/backend/src/main/java/my/portfoliomanager/app/importer/`.
2. Implement `DepotParser`.
3. Update `ImportService` parser map to include the new depot code.
4. Update UI labels/validation for allowed file types if needed.

### Parsing correctness goals
- Extract **clean instrument name** (without price, “ISIN:”, quantity, date).
- Extract `value_eur` whenever present. If not present, store `NULL` and let the UI/queries handle it.
- Deduplicate positions within a file by ISIN **before** writing to DB.

### Avoid common SQL issues
- `ON CONFLICT DO UPDATE command cannot affect row a second time`
  - Root cause: duplicates (same constrained key) within the same INSERT statement.
  - Fix: **dedupe before DB writes** (e.g. map keyed by ISIN in the importer/service) or insert into a stage table and aggregate.

---

## 6) Rules engine (how to think about it)

Rules should classify instruments without manual DB edits:
- matchers commonly used:
  - `match_isin_re` (regex)
  - `match_name_re` (regex, case-insensitive)

Rule application strategy:
1. Build a “candidate classification” table (temporary or persistent)
2. Apply in deterministic order (priority)
3. Write results back to `instrument_classifications` (effective view merges with overrides)
4. Update `layer_last_changed` only when layer changed

Important: All unknown instruments should remain `layer=5` until explicitly classified.

---
## 6a) Layer target profiles & advisor config

- Five advisor profiles (Classic → Opportunity) live in `services/app/backend/src/main/resources/layer_targets.json`. Each includes layer targets, an acceptable variance (default 3%), a minimum saving plan size (integer EUR, default 15), a minimum rebalancing amount (integer EUR, default 10), max saving plans per layer (default 17), and constraint thresholds (core minimum, layer 4/5 caps) so the advisor can warn about violations even before tolerance checks.
- The `layer_target_config` table stores a single row (`id=1`). `LayerTargetConfigService` and Liquibase ensure the JSON supports the new schema plus backwards compatibility for legacy `{layer_targets, acceptable_variance_pct}` payloads. The service feeds `LayerTargetEffectiveConfig` to the advisor, merges custom overrides when enabled, and exposes `LayerTargetConfigResponseDto` to the UI.
- API endpoints: `GET /api/layer-targets`, `PUT /api/layer-targets`, `POST /api/layer-targets/reset`. They supply/accept the active profile key, profile display metadata, effective layer targets, variance, layer name mapping, and custom override flags. Liquibase handles default inserts for Postgres, migrating legacy configs into the new shape.
- The Vue UI (`LayerTargetsView.vue`) renders the “Advisor Profile” panel + new layer target table using the API response. It lets you switch profiles, enable overrides, edit per-layer weights (0–1), variance, minimum saving plan size, minimum rebalancing amount, and max saving plans per layer, and see delta warnings alongside layer names (Global Core, Core-Plus, Themes, Individual Stocks, Unclassified). Overridden weights flow back into the advisor narrative so the LLM and summaries always describe the actual profile in play.

---

## 7) Developer workflows for Codex

### Start stack
- `docker compose up -d --build`
- If tests fail against running containers, inspect logs:
  - `docker compose logs --tail=200 db_portfolio`
  - `docker compose logs --tail=200 admin_spring`
  - `docker compose logs --tail=200 admin_frontend`

### Start E2E stack (isolated)
- Preferred: `services/app/frontend/scripts/run-e2e.sh`
  - Builds the stack, waits for readiness, runs Playwright e2e tests, and tears the stack down afterward.
- Manual alternative (only if needed):
  - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e up -d --build`
  - Runs Spring + UI on `127.0.0.1:18089` and `127.0.0.1:18090` with hardcoded e2e credentials. The e2e Postgres uses container storage (no named volume); data is discarded on teardown.
  - If tests fail against running containers, inspect logs:
    - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e logs --tail=200 e2e_db_portfolio`
    - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e logs --tail=200 e2e_admin_spring`
    - `docker compose -f services/app/frontend/docker-compose.yml -p portfoliomanager_e2e logs --tail=200 e2e_admin_frontend`

### Reset database completely (schema changes)
Default setup uses named volumes. For a full reset:
- Stop + remove volumes: `docker compose down -v`
- Start stack again: `docker compose up -d --build` (Liquibase applies migrations on startup)
If you use a bind mount like `./data/postgres_portfolio:/var/lib/postgresql/data`, delete the bind-mounted directory instead.

### Import depot statements
Use the Admin UI (Import page). The upload updates instruments, applies rules, and writes snapshots.

### Validate imports quickly
- `docker compose exec db_portfolio psql -U portfolio -d portfolio -c "SELECT * FROM import_files ORDER BY imported_at DESC LIMIT 10;"`
- `docker compose exec db_portfolio psql -U portfolio -d portfolio -c "SELECT COUNT(*) FROM snapshot_positions;"`

### Run unit tests
- Spring Boot (Postgres int-test env + Liquibase + JaCoCo coverage gate):
  - Start DB: `docker compose -f services/app/backend/int-test-env/compose.yaml up -d` (exposes Postgres on `localhost:5439`, db `mydatabase`, user `myuser`, password `secret`)
  - `cd services/app/backend`
  - `GRADLE_USER_HOME=/home/user/git/portfoliomanager/services/app/backend/.gradle_home ./gradlew test`
- Frontend (Vitest):
  - `cd services/app/frontend`
  - `npm test`
- Frontend tooling expects Node `v25.3.0`; keep local runtimes and Docker build images aligned with this version.
- Always run tests for any component you changed (backend, frontend, or e2e) before handing off work.
- CI: GitHub Actions workflows in `.github/workflows` run frontend build + Vitest and backend test/build against Postgres 16 using `DB_URL/DB_USER/DB_PASSWORD`; keep these aligned with test config if they change.

### Run Spring Boot tests + coverage
- `cd services/app/backend`
- Use `GRADLE_USER_HOME=/home/user/git/portfoliomanager/services/app/backend/.gradle_home` for agent runs to avoid permission issues.
- `./gradlew test` (Postgres via int-test env + Liquibase + JaCoCo coverage gate; Postgres must be running as above)
- If Gradle fails with `Could not determine a usable wildcard IP for this machine` (common in restricted sandboxes), run the backend build + integration tests by building the backend Docker image instead:
  - `docker compose build admin_spring` (runs `/opt/gradle/bin/gradle clean check bootJar` inside the container build; add `--no-cache` to force rerun)
  - The backend Dockerfile copies Gradle/JDK from `gradle:9.2-jdk21` and uses a `postgres:16` build stage to provision a temporary Postgres on `localhost:5439`, so the build does not require an external DB.

### Postgres-specific column types (JSONB)
- Integration tests now run on PostgreSQL (int-test env). Keep explicit JSONB handling where needed:
  - `advisor_runs.depot_scope`, `advisor_runs.summary_json`
  - `layer_target_config.config_json`
- If you write to JSONB via `JdbcTemplate`, use an explicit cast like `cast(? as jsonb)` (or a driver-typed JSON parameter). Do not rely on implicit varchar→jsonb conversion.
- H2 is no longer used; avoid adding H2-specific configs or Liquibase changesets and keep docs Postgres-only.

---

## 8) Common TODOs / future improvements

- Optionally archive uploaded files after depot statement import
- Add optional layer overrides ingestion (if you want overrides beyond rules + manual UI edits)
- Add a rules preview/diff before applying
- Add audit tables for instrument metadata edits via Admin UI
- Add export/import for instrument metadata (JSON/CSV)

---

## 9) “Where to change what” (cheat sheet)

- **Parsing fixes**:
  - TR PDF parsing: `services/app/backend/src/main/java/my/portfoliomanager/app/importer/TrPdfParser.java`
  - Deka CSV parsing: `services/app/backend/src/main/java/my/portfoliomanager/app/importer/DekaCsvParser.java`

- **Depot statement import / snapshot idempotency**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/ImportService.java`
  - `services/app/frontend/src/views/ImportsExportsView.vue` (form moved to the new Imports & Exports view at `/imports-exports`)

- **Savings plans UI**:
- `services/app/backend/src/main/java/my/portfoliomanager/app/api/SavingPlanController.java`
  - `services/app/frontend/src/views/SavingsPlansView.vue`

- **Reclassification Rulesets UI**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/RulesetController.java`
  - `services/app/frontend/src/views/RulesetsView.vue`
- **Imports & Exports UI**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/OverridesController.java` (CSV import/export)
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/BackupController.java` (database backup export/import)
  - `services/app/frontend/src/views/ImportsExportsView.vue` (new combined view; `/imports-exports` replaces `/import` + `/overrides`)
  - Snapshot export (previous `/api/snapshots/export`) was removed; the new backup endpoint exports zipped JSON per table with `metadata.formatVersion = 1` and SHA-256 digests; imports truncate all user tables, verify hashes, and reload the database before resetting sequences.
- **Instrument effective listing**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/InstrumentController.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/InstrumentEffectiveService.java`
- **Reclassifications (review + apply)**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/AdvisorController.java` (`GET /api/advisor/reclassifications`)
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/RulesetController.java` (`POST /api/rulesets/{name}/apply`)
  - Reclassification suggestions prefer Knowledge Base extraction fields by ISIN; ruleset output is used when KB data is missing.
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/ClassificationService.java`
  - `services/app/frontend/src/views/ReclassificationsView.vue`

- **Advisor analysis + UI**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/AdvisorController.java`
  - `services/app/frontend/src/views/AdvisorSummaryView.vue`
  - `services/app/frontend/src/views/AdvisorHistoryView.vue`
- **Assessor analysis + UI**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/AssessorController.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorService.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/AssessorEngine.java`
  - `services/app/frontend/src/views/AssessorView.vue`
  - Assessor responses include new instrument suggestions for saving plans and one-time allocations when KB coverage shows missing themes/regions/sub-classes and budgets allow it; narratives must explain the gap and why each instrument fits.
- **Layer targets config UI**:
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/LayerTargetConfigController.java`
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/LayerTargetConfigService.java`
  - `services/app/frontend/src/views/LayerTargetsView.vue`
- `services/app/backend/src/main/resources/layer_targets.json` (targets config defaults)
  - `services/app/backend/src/main/resources/db/changelog/db.changelog-master.yaml` (schema incl. advisor_runs)

- **Spring Boot rulesets + advisor**:
  - API controllers: `services/app/backend/src/main/java/my/portfoliomanager/app/api`
  - Services: `services/app/backend/src/main/java/my/portfoliomanager/app/service`
  - Rules engine: `services/app/backend/src/main/java/my/portfoliomanager/app/rules`
  - Liquibase: `services/app/backend/src/main/resources/db/changelog/db.changelog-master.yaml`
  - Vue UI: `services/app/frontend/src`
- **LLM port (mockable)**:
  - Interface + implementations: `services/app/backend/src/main/java/my/portfoliomanager/app/llm`
  - Savings plan advisor uses LLM (if enabled) for narrative/explanation only; proposal targets remain deterministic (rule-based).

---

## Coordination note
- Whenever the database schema structure changes (new/removed tables, new columns, constraints, or default rows), update the backup/export logic documented above so the exported JSON set and import truncation order cover the new schema; missing this causes backups to open with inconsistent table lists or to silently drop data.

## Backend validation
- Backend changes must always be validated via the containerized Gradle build/test pipeline because the host Gradle run is unreliable here. Preferred commands:
  1. `docker compose build --progress=plain admin_spring` (runs `./gradlew clean bootJar --no-daemon` inside the backend image and prints full compiler/test/coverage output).
  2. `docker compose run --rm admin_spring /bin/sh -c "cd /app && ./gradlew <task> --stacktrace"` for specific `build`, `test`, or coverage targets when you need extra diagnostics.
  3. `docker build --progress=plain services/app/backend` if you only need the build step without the Compose stack.
  4. Always confirm that the required tests/reporting tasks succeeded after any backend change before finishing work so regressions are caught early.
  5. When Docker commands require it, run them with escalated permissions so `docker compose build` can reach `/var/run/docker.sock`; this is necessary in environments where the user-level daemon socket is blocked.
  6. If `./gradlew` fails locally because of sandbox/network restrictions (wildcard IP errors, socket permissions, etc.), rerun `docker compose --progress=plain build admin_spring` as the documented fallback and treat the container build result as the authoritative verification.

## 10) Operational principles (keep things stable)

- Do not manually edit tables in production; prefer:
  1) rules + apply, 2) Admin UI single-record edits, 3) reimport
- Treat file imports as immutable snapshots; store corrections in instrument master data (not in snapshot rows).
- Keep “unclassified” instruments as Layer 5 until correctly mapped.

## 11) Documentation  update

- On every change check if AGENTS.MD and other documentation needs to be updated and update documentation.
- Add a short instruction in AGENTS.MD whenever new agent actions/configuration require special coordination, ensuring future sessions know to adjust this file proactively.
- In case of missmatches between AGENTS.MD, other documentation and the real project contents always update AGENTS.MD and documentation to match the reality.

---

## 12) UI Usability, Semantik & Accessibility Review (verpflichtend)

This section is mandatory for any UI change or any detected UI issue. It must be applied by Codex and humans alike.

### UI Change Detection (wann die Review zwingend ist)
Run this review if ANY of the following is true:
- Changes under UI paths: `services/app/frontend/src/components/**`, `services/app/frontend/src/views/**`, `services/app/frontend/src/pages/**`, `services/app/frontend/src/layouts/**`, `services/app/frontend/src/styles/**`, `services/app/frontend/src/assets/**`
- Changes to UI-relevant routing or state flows (router, stores, view models) that alter navigation or presentation
- Changes to component library usage, design tokens, typography, spacing, colors, icons, or layout primitives
- Any reported/observed UI defect or usability/clarity issue, even without code changes

### Nicht verhandelbar (harte Regeln)
- No "div-only" data lists when content is tabular (rows/columns comparison).
- No fake tables via CSS Grid/Flex when a semantic table is required.
- No unclear visual hierarchy: missing heading structure, poor grouping, or inconsistent states (Loading/Empty/Error).

### Entscheidungsregel (immer anwenden)
- If records are rows and users compare, sort, or scan columns quickly: use `<table>`.
- If it is a linear list without column comparison: use `<ul>`/`<ol>`.
- If it is label–value pairs: use `<dl>`.
If you deviate, you MUST justify and document it in 2–3 sentences in the review output.

### Tabellen-Mindeststandard (Checkliste)
- `<table>` with `<thead>/<tbody>`
- Column headers are `<th scope="col">`, row headers (if any) are `<th scope="row">`
- `caption` present when meaningful (can be visually hidden)
- Keyboard usable (focus order), no interaction only via hover
- Sorting: `aria-sort` on headers + explicit sort buttons
- Responsive behavior defined: horizontal scroll OR row details/stacking; never unreadable mini-columns
- States are clear and consistent: Loading/Empty/Error

### Ubersichtlichkeit / Scannability Scorecard (0-2 je Kriterium)
Score each criterion 0–2 and add a short rationale:
- Scannability: alignment, row height, column widths, wrap rules, consistent value formatting
- Visual hierarchy: heading levels, grouping, spacing, contrast, progressive disclosure
- Information density: no overload, secondary info de-prioritized (collapsed/tooltip/details)
- Interaction clarity: clear actions, consistent states, understandable microcopy
- Semantics: correct `<table>`/`<ul>`/`<dl>`, no div soup for tabular data
- A11y basics: labels, focus, keyboard, ARIA only when needed, form error messaging

### Severity scale (must be used in review output)
- S0 critical: blocker, wrong semantics for data comparison, not keyboard operable, central flow blocked
- S1 high: major comprehensibility/defect risk, inconsistent interactions/states
- S2 medium: UX friction, suboptimal hierarchy/spacing but usable
- S3 low: cosmetic/polish

### Review output (pflicht; in PR description or commit/review log)
If UI is affected or a UI issue is found, include:
A) Scorecard (0–2 per criterion) + 3–10 key findings (each with Severity, Impact, Recommendation)
B) Brief "Before/After" structure note (what changed for clarity/semantics)
C) Confirmation that the table/list/dl decision rule was checked (with justification if deviating)

Template (copy/paste):
```
UI Review
- Decision rule checked: <table|ul/ol|dl> (deviation rationale if any)
- Before/After: <short note>

Scorecard (0-2):
- Scannability: <0-2> – <reason>
- Visual hierarchy: <0-2> – <reason>
- Information density: <0-2> – <reason>
- Interaction clarity: <0-2> – <reason>
- Semantics: <0-2> – <reason>
- A11y basics: <0-2> – <reason>

Findings (3-10):
- [S#] <issue> — Impact: <impact> — Recommendation: <fix>
```

## 13) Keep AGENTS.MD current

- Whenever you learn something new that affects coordination (new requirements, tooling behavior, environment quirks, or handover notes), add a short entry to AGENTS.MD describing the change. Treat this guide as living documentation so future sessions automatically benefit from the latest context and avoid repeating the same problems.
- When `gradlew test` fails with “usable wildcard IP,” `docker build services/app/backend` still runs `./gradlew clean bootJar --no-daemon`, so rely on that container build to cover tests/JaCoCo verification.

## 14) Knowledge Base notes

- Knowledge Base endpoints are gated by `KB_ENABLED=true` and a configured LLM provider; when LLM is not configured, `/api/kb/**` returns 503.
- Backup/import now includes `instrument_dossiers`, `instrument_dossier_extractions`, `instrument_facts`, `kb_config`, `kb_runs`, and `kb_alternatives` in the import order/sequence reset lists; keep BackupService in sync when KB schema changes.
- Knowledge base dossiers no longer require a matching instrument; unknown ISINs are allowed and may not exist in `instruments`.
- Knowledge Base UI can generate a dossier draft via async websearch jobs:
  - `POST /api/kb/dossiers/websearch` returns `{jobId,status}` quickly
  - `GET /api/kb/dossiers/websearch/{jobId}` returns `{status,result,error}` where `result` is `{contentMd,displayName,citations,model}`
- `KB_LLM_ENABLED=true` enables `LlmExtractorService` for dossier extraction runs (currently stubbed; adds a warning and uses the stub extractor payload).
- `instrument_dossiers.display_name` is optional and used to display/search dossiers for unknown ISINs.
- Async KB LLM job failures surface `Error ref KB-XXXXXXXX` in UI; logs include the same ref for troubleshooting.
- OpenAI client timeouts are configurable via `app.llm.openai.connect-timeout-seconds` and `app.llm.openai.read-timeout-seconds`.
- Bulk research parallelism uses `max_parallel_bulk_batches` in KB settings (default 2).

### Automatic application
If you change UI files or observe UI issues, run this review and add the output to the PR description or commit/review log.

### Advisor instrument proposals
- Instrument-level saving plan proposals are gated by `app.kb.enabled` and the latest `knowledge_base_extractions` status (`COMPLETE`). When gating fails, advisor responses include missing ISINs and no instrument-level proposals.
- `instrument_dossier_extractions` are synced into `knowledge_base_extractions` on extraction run/approve/apply.
