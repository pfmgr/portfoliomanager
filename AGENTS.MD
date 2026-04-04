# AGENTS.MD — Portfolio Stack

This repository contains a small, self-hosted portfolio tracker for **Trade Republic (TR) PDF** and **Deka CSV** exports.
It imports positions into PostgreSQL and manages savings plans via the **Spring Boot Admin API + Vue 3 UI**, then lets you classify instruments (type/asset class/layer) using a **rules engine**. The Admin UI authenticates via JWT login only.
The current stack is a **Spring Boot Admin API** plus a **Vue 3 UI** under `services/app/` for imports, reclassification rulesets, reclassifications, and rebalancer summaries.

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
  - running rebalancer analysis
- Depot statement imports can trigger rule application automatically.

**Database (PostgreSQL)**
- Stores master data, savings plans (`sparplans` table), file import history, snapshots, and snapshot positions.
- Layer concept:
  - Layers `1..4` = your “Core-Satellite” tiers
  - Layer `5` = **unclassified** (default for newly discovered instruments)

---

## 2) Services & composition

Services in `docker-compose.yml`:
- `db_portfolio` (Postgres 16.x; uses `portfoliomanager-postgres:16` image that must be available locally or pulled from a registry)
- `admin_spring` (Spring Boot; reclassification rulesets + rebalancer API; JWT issuance)
- `admin_frontend` (Vue 3 + Vite build served by Nginx; proxies /api to admin_spring)
- `metabase` + `db_metabase` (BI dashboard; included in `docker-compose.yml`). If you don't need it, start only the core services or remove these services.
Root `docker-compose.yml` only references images; build the app images with `build_docker_containers.sh` or pull them from docker.io before starting the stack.

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
  - `services/app/backend/src/main/resources/layer_targets.json` (rebalancer target weights + variance tolerance defaults)
  - `services/app/backend/src/main/resources/default_classification_ruleset.json` (default reclassification ruleset loaded on first start)
- `services/app/frontend/` (Vue 3 + Vite UI served via Nginx)
  - `services/app/frontend/src/` (UI code)
  - `services/app/frontend/tests/unit/` (Vitest unit tests)
  - `services/app/frontend/tests/e2e/` (Playwright e2e tests)
  - `services/app/frontend/scripts/run-e2e.sh` (preferred e2e runner)

If your repo differs, **Codex should search by symbol/function name** rather than relying on paths.

## 3a) Feature scenarios (source of truth)

Feature-level behavior and edge cases are documented in scenario files:

- `scenarios/rebalancer.md`
- `scenarios/assessor.md`
- `scenarios/saving-plans.md`
- `scenarios/knowledgebase-extraction.md`
- `scenarios/llm-websearch.md`

OpenCode command wrappers for these scenarios are available in `.opencode/commands/`.

## 3b) Scenario lifecycle and test planning (mandatory)

- Scenario updates are required for every feature change.
  - If an existing feature behavior changes, update the corresponding `scenarios/*.md` in the same task.
  - If a new feature is introduced, create a new `scenarios/<feature>.md` in the same task.
- Scenario updates must also keep verification skills current.
  - If an existing skill still matches the new behavior, reference that skill explicitly from the scenario section that owns the behavior.
  - If no existing skill cleanly covers the new workflow, add or extend a `.opencode/skills/*/SKILL.md` file in the same task.
  - Prefer scenario references to skills over raw script paths so agents can execute the right workflow without rediscovering commands.
- Implementation plans must include scenario work explicitly (create/update scenario + tests derived from scenario).
- Test planning must always be scenario-driven:
  - Derive test cases from scenario sections such as core behavior, API contracts, data dependencies, and edge cases.
  - If scenario and implementation differ, update the scenario first or alongside code.
- A change is not complete until code, tests, and scenario docs are aligned.

---

## 4) Database schema (conceptual)

Minimum tables:
- `depots(depot_id, depot_code, name, provider, active_snapshot_id, ...)`
- `instruments(isin PK, name NOT NULL, depot_code REFERENCES depots.depot_code DEFAULT 'tr', instrument_type, asset_class, sub_class, layer, is_deleted, ...)`
- `sparplans(...)` and `sparplans_history(...)`
- `import_files(...)` (dedupe file imports by hash)
- `snapshots(...)`
- `snapshot_positions(snapshot_id, isin, shares, value_eur, ...)`
- `advisor_runs(...)` (rebalancer history)
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
- Feature-specific precedence details are documented in `scenarios/knowledgebase-extraction.md`.
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
## 6a) Layer target profiles & rebalancer config

- Detailed feature behavior has moved to scenario docs:
  - `scenarios/rebalancer.md`
  - `scenarios/assessor.md`

---

## 7) Developer workflows

Build/test/run instructions are maintained in OpenCode skills:

- `.opencode/skills/scenario-lifecycle/SKILL.md`
- `.opencode/skills/docker-build-and-stack/SKILL.md`
- `.opencode/skills/backend-junit-tests/SKILL.md`
- `.opencode/skills/frontend-vitest-tests/SKILL.md`
- `.opencode/skills/frontend-e2e-tests/SKILL.md`
- `.opencode/skills/frontend-running-stack-e2e-tests/SKILL.md`
- `.opencode/skills/running-instance-smoke-tests/SKILL.md`
- `.opencode/skills/running-stack-fixtures/SKILL.md`

OpenCode command wrappers for scenario loading are under `.opencode/commands/`.

### Reset database completely (schema changes)
Default setup uses named volumes. For a full reset:
- Stop + remove volumes: `docker compose down -v`
- Start stack again: `docker compose up -d` (Liquibase applies migrations on startup; rebuild images first if needed)
If you use a bind mount like `./data/postgres_portfolio:/var/lib/postgresql/data`, delete the bind-mounted directory instead.

### Import depot statements
Use the Admin UI (Import page). The upload updates instruments, applies rules, and writes snapshots.

### Validate imports quickly
- `docker compose exec db_portfolio psql -U portfolio -d portfolio -c "SELECT * FROM import_files ORDER BY imported_at DESC LIMIT 10;"`
- `docker compose exec db_portfolio psql -U portfolio -d portfolio -c "SELECT COUNT(*) FROM snapshot_positions;"`

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
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/RebalancerController.java` (`GET /api/rebalancer/reclassifications`)
  - `services/app/backend/src/main/java/my/portfoliomanager/app/api/RulesetController.java` (`POST /api/rulesets/{name}/apply`)
  - Behavior details are documented in `scenarios/knowledgebase-extraction.md`.
  - `services/app/backend/src/main/java/my/portfoliomanager/app/service/ClassificationService.java`
  - `services/app/frontend/src/views/ReclassificationsView.vue`

- **Feature scenarios (source of truth)**:
  - `scenarios/rebalancer.md`
  - `scenarios/assessor.md`
  - `scenarios/saving-plans.md`
  - `scenarios/knowledgebase-extraction.md`
  - `scenarios/llm-websearch.md`

- **Feature code roots**:
  - Backend APIs: `services/app/backend/src/main/java/my/portfoliomanager/app/api`
  - Backend services: `services/app/backend/src/main/java/my/portfoliomanager/app/service`
  - Rules engine: `services/app/backend/src/main/java/my/portfoliomanager/app/rules`
  - LLM abstraction: `services/app/backend/src/main/java/my/portfoliomanager/app/llm`
  - Frontend views: `services/app/frontend/src/views`

---

## Coordination note
- Whenever the database schema structure changes (new/removed tables, new columns, constraints, or default rows), update the backup/export logic documented above so the exported JSON set and import truncation order cover the new schema; missing this causes backups to open with inconsistent table lists or to silently drop data.

## Backend validation
- Backend changes must always be validated with full integration tests.
- Use `.opencode/skills/backend-junit-tests/SKILL.md` for canonical backend test instructions.
- Use `.opencode/skills/docker-build-and-stack/SKILL.md` for canonical container build instructions.
- Always select and justify backend test cases from relevant `scenarios/*.md`.

## 10) Operational principles (keep things stable)

- Do not manually edit tables in production; prefer:
  1) rules + apply, 2) Admin UI single-record edits, 3) reimport
- Treat file imports as immutable snapshots; store corrections in instrument master data (not in snapshot rows).
- Keep “unclassified” instruments as Layer 5 until correctly mapped.

## 11) Documentation  update

- On every change check if AGENTS.MD and other documentation needs to be updated and update documentation.
- Add a short instruction in AGENTS.MD whenever new agent actions/configuration require special coordination, ensuring future sessions know to adjust this file proactively.
- In case of missmatches between AGENTS.MD, other documentation and the real project contents always update AGENTS.MD and documentation to match the reality.
- Treat `scenarios/*.md` as required living docs for feature behavior; every feature change/new feature must update or add scenarios in the same change set.
- Treat `.opencode/skills/*/SKILL.md` as required living docs for executable agent workflows; every new runtime/test workflow must update or add a skill in the same change set when existing skills are insufficient.

---

## 12) UI Usability, Semantik & Accessibility Review (verpflichtend)

This section is mandatory for any UI change or any detected UI issue. It must be applied by Codex and humans alike.

### UI Change Detection 
Run this review if ANY of the following is true:
- Changes under UI paths: `services/app/frontend/src/components/**`, `services/app/frontend/src/views/**`, `services/app/frontend/src/pages/**`, `services/app/frontend/src/layouts/**`, `services/app/frontend/src/styles/**`, `services/app/frontend/src/assets/**`
- Changes to UI-relevant routing or state flows (router, stores, view models) that alter navigation or presentation
- Changes to component library usage, design tokens, typography, spacing, colors, icons, or layout primitives
- Any reported/observed UI defect or usability/clarity issue, even without code changes

### Must-Have Rules
- No "div-only" data lists when content is tabular (rows/columns comparison).
- No fake tables via CSS Grid/Flex when a semantic table is required.
- No unclear visual hierarchy: missing heading structure, poor grouping, or inconsistent states (Loading/Empty/Error).

### Decission Rules
- If records are rows and users compare, sort, or scan columns quickly: use `<table>`.
- If it is a linear list without column comparison: use `<ul>`/`<ol>`.
- If it is label–value pairs: use `<dl>`.
If you deviate, you MUST justify and document it in 2–3 sentences in the review output.

### Tabele minimum standards
- `<table>` with `<thead>/<tbody>`
- Column headers are `<th scope="col">`, row headers (if any) are `<th scope="row">`
- `caption` present when meaningful (can be visually hidden)
- Keyboard usable (focus order), no interaction only via hover
- Sorting: `aria-sort` on headers + explicit sort buttons
- Responsive behavior defined: horizontal scroll OR row details/stacking; never unreadable mini-columns
- States are clear and consistent: Loading/Empty/Error

### Scannability Scorecard (0-2 per criterion)
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

### Review output (mandatory; in PR description or commit/review log)
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
- Keep `.opencode/skills/*/SKILL.md` and `scenarios/*.md` aligned with reality.
- For new feature requirements, extend the owning `scenarios/*.md` and attach the relevant verification skills there. If a required verification flow has no suitable skill yet, create or extend the skill before considering the task complete.

## 14) Knowledge Base notes

- KB details are maintained in scenario docs:
  - `scenarios/knowledgebase-extraction.md`
  - `scenarios/llm-websearch.md`

### Automatic application
If you change UI files or observe UI issues, run this review and add the output to the PR description or commit/review log.

### Rebalancer instrument proposals
- See `scenarios/rebalancer.md` and `scenarios/knowledgebase-extraction.md`.

---

## 15) Security & Critical Review (mandatory)

- Every change must include a brief critical review covering: security weaknesses, data leaks, injection vectors, and code duplication.
- Review output must be recorded in the PR description or commit/review log (short list of findings + severity + recommended actions).
- Always run tests after hardening changes: backend JUnit, frontend Vitest, frontend E2E (see `.opencode/skills/*/SKILL.md`).
- Test scope must be derived from relevant scenario docs, not ad-hoc command selection.

### LLM Prompt Minimization Review (required)
- Prompt templates defined in Java classes MUST be reviewed for need-to-know data before external LLM calls.
- Allowed in prompts: ISINs, instrument names, absolute and relative amounts (when needed for explanations).
- Disallowed in prompts: user identifiers (depot IDs, account IDs, usernames, tokens, emails, addresses, phone numbers).
- Policy applies only to external LLM providers.
  - External is true when `app.llm.external-provider=true` OR the provider is enabled and the base URL is not localhost (combined A/B rule).
- If a prompt contains disallowed data, the external LLM call must be skipped (fallback narrative or error).

### Lombok Usage Review (optional)
- Evaluate Lombok only for plain model/POJO classes to reduce boilerplate.
- Do NOT use Lombok on JPA entities (risk of Equals/HashCode pitfalls).

## 16) Development workflow
Before starting implementation, actively choose the minimal effective split across `dev1`, `dev2`, and `dev3`. Using only one or two dev subagents is explicitly valid and often preferred when it keeps coordination lean.

Re-evaluate that split whenever issues are found during implementation, review, security review, UX review, or testing. It is valid to continue without dev subagents, keep the current set, reduce to fewer dev subagents, or bring in additional dev subagents if that is the most efficient way to resolve the newly discovered work.

Use `@review` after implementation for every non-trivial change.
Use `@uxreview` proactively for UI, navigation, routing, layout, hierarchy, or discoverability changes; skip it for purely internal or invisible refactors.
Use `@security-auditor` proactively for auth, roles, secrets, LLM, backup/export/import, routing of sensitive flows, caching, storage, or logging changes; skip it for changes without a security-relevant surface.
Use `@tester` proactively whenever behavior, navigation, forms, or rendered output should be verified; skip it for trivial changes with no meaningful runtime effect.

Issues found during the dev-review-test workflow should be fixed without further user consultation whenever the fix is clear, local, and aligned with the current task. After each finding, briefly reassess whether dev subagents are needed and which specific dev subagents are the most efficient choice for the follow-up fix.

After implementation changes must be tested by `@tester` when applicable, and any problems encountered during testing and/or verification should be resolved without further consultation.
