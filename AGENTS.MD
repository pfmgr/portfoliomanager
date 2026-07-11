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
- Local TLS and optional backend exposure are configured through `configure-docker-compose.sh` and `.local/docker-compose.override.yml`.
Root `docker-compose.yml` only references images; build the app images with `build_docker_containers.sh` or pull them from docker.io before starting the stack.

Ports / binds:
- Services should bind only to LAN interfaces (e.g. `192.168.x.x`) or be fronted by a LAN-only reverse proxy.
- JWT auth is used for the Admin UI; the frontend does not use Basic Auth.
- `admin_spring` is not host-exposed by default; use the compose wizard's optional debug exposure if you need a host port.

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
@uxreview bewertet ausschließlich UI/UX-/A11y-Findings anhand von Code, Szenarien und vom @test-runner gelieferten Ausführungsevidenzen. Es ist der alleinige Owner des UX-/A11y-Review-Gates und schließt es selbst; @testmanager entscheidet ausschließlich das Test-Gate. Es führt keine E2E-, Frontend-, A11y-, Backend-, Build-, Container- oder sonstigen Tests/Validierungen aus.

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
A) Scorecard (0–2 per criterion) + 0–10 key findings (each with Severity, Impact, Recommendation); if there are none, state the explicit status `keine Findings`
B) Brief "Before/After" structure note (what changed for clarity/semantics)
C) Confirmation that the table/list/dl decision rule was checked (with justification if deviating)
D) Test-runner-Evidenz referenziert (wenn E2E/Axe nötig ist, kommt die Evidenz ausschließlich von @test-runner; die UX-Gateentscheidung trifft @uxreview selbst)

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

Findings (0-10):
- Status: <keine Findings | Findings vorhanden>
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
- Always run tests after hardening changes: `@test-runner` executes backend JUnit, frontend Vitest, frontend E2E, automated A11y checks, smoke-, stack-, container-/runtime-, and other required tests; `@testmanager` evaluates the test evidence and decides the Test-Gate, while `@uxreview` closes the UX-/A11y-Review-Gate from `@test-runner` evidence (see `.opencode/skills/*/SKILL.md`).
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
This is the default mandatory workflow for every implementation and bug fix. Do not skip a stage unless it is inapplicable; record the reason in the handover or review log.

### Global redaction and need-to-know rule
- Handover documents, commit/review logs, review and test reports, PRs/Issues, root-cause analyses, and exception records must never contain secrets or unnecessary personal data. Use only need-to-know information and safe references (for example, redacted identifiers, issue links, or secret-manager references), never credentials, tokens, passwords, private keys, session cookies, or raw personal data.

### 1. Plan and implement
- Derive scope, acceptance criteria, scenario updates, and test cases from the relevant `scenarios/*.md` before changing code.
- New features: create/update the scenario first. Infra- or documentation-only work without a scenario derives acceptance criteria from the user request plus existing operational docs.
- Before implementation, task `@testmanager` for every new or changing requirement, feature, bugfix, and behavior/API/data model/UI/security/infrastructure/dependency change. Exception: pure documentation changes without runtime/configuration/behavior impact, with a documented reason.
- Workflow: `@testmanager` plans -> devs implement tests/code -> `@review-preflight` on `openai/gpt-5.6-luna` runs read-only Diff-Triage after implementation and before any Deep Review, build, or test -> applicable Terra Deep Review gates -> `@build` -> `@test-runner` executes preflight and final tests -> `@testmanager` evaluates gaps and the Test-Gate; if UI is affected, `@uxreview` separately closes the UX-/A11y-Review-Gate from `@test-runner` evidence.
- `@review-preflight` is read-only and only publishes the risk class, finding fingerprint, root-cause ID, impact matrix, and deep-gate route. Its diff context comes from the orchestrating task handoff; it must not synthesize a generic shell diff dump.
- Java changes require exactly one Sonar scan per Java preflight attempt via `.opencode/skills/sonarqube-java-review/SKILL.md`, after `@review-preflight` Diff-Triage and the explicit testfreie Preflight-Kompilierung, but before every Test-Gate. A code fix starts a new preflight attempt and therefore a new Sonar scan. `@review-preflight` owns the Preflight-Gate. If Sonar cannot start or becomes unhealthy, the initial status is `blocked` as an infrastructure blocker. Recovery requires explicit human approval; it must never delete volumes automatically. After approved recovery, retry exactly once; a further infrastructure failure remains `blocked`.
- Sonar semantics: Green Sonar is the only preflight `pass`. Sonar Quality Gate `ERROR`, a failed analysis, or any failed scan makes the Java preflight `fail`. If the single recovery retry still leaves the stack unhealthy or the scan cannot complete, mark the preflight `blocked` as an infrastructure blocker. A Java task is complete only if the handover records a matching green Sonar result for the final code state.
- Split work into the smallest independently implementable and verifiable tasks. Each task must name its acceptance criteria, affected area, and exactly one implementation owner.
- Delegate implementation tasks through the Task tool to `@dev1`, `@dev2`, and `@dev3`. Use the smallest useful number of agents; parallelize only tasks with non-overlapping file ownership.
- Give `@seniordev` only tasks that cannot be reduced further without creating artificial coupling, or that require complex cross-cutting design decisions.
- Include all relevant context, prior findings, affected files, scenario requirements, required skills/commands, and the task handover schema in every delegation.
- Prompt minimization is mandatory: share only need-to-know data, never secrets, credentials, tokens, passwords, private keys, session cookies, or other unnecessary personal data.
- The owning implementation agent fixes clear, local findings without asking the user again. Reassign ownership only when the finding belongs to a different component.

### 2. Review gates and fix loop
- After implementation, delegate a code review to `@review` for every change except pure documentation-only edits with no runtime-, configuration-, or behavior impact. Treat code, tests, runtime configuration, container/Compose/Dockerfile, CI/CD, scripts, and any behavior-changing change as review-relevant by default; if unsure, review.
- `@review-preflight` on `openai/gpt-5.6-luna` always runs first as read-only Diff-Triage. It never fixes code and only routes the change through the risk class and impact matrix.
- Deep-Routing-Matrix: `critical`/`high` preflight or S0/S1/unclear S2 => Terra `@review`; every Security-Impact => `@security-auditor`; every UI-Impact => `@uxreview`. The listed High-Risk categories always require `@review`. Terra is also mandatory for all Low-Risk diffs until a documented shadow comparison of ten Low-Risk diffs proves the quality criteria; only then may a maintainer approve Luna-only Low-Risk diffs.
- Terra Deep Review is mandatory for Auth, Secrets, LLM, DB/Liquibase/Migrations/Transactions, Import/Idempotency, API contracts, concurrency, performance, Container/Compose/CI, and every Luna result classified S0/S1 or unclear S2.
- **Luna-only shadow register:** Until ten sequential Low-Risk comparisons are complete, Terra review is mandatory. Store the register at `.opencode/handover/shadow-review.md`; each entry records one distinct Low-Risk diff, fixed fields for diff reference, risk class, path/scope, findings, fingerprint, root-cause ID, severity, deep-gate route, Luna/Terra parity, redacted time/token cost, and maintainer approval. The ten required entries are `LR-01` through `LR-10`; a comparison passes only if Terra finds no S0/S1 or missed blocking gate and any variance is documented. Luna-only Low-Risk operation remains forbidden until all ten pass and a traceable maintainer records explicit identity, date, approval, and evidence reference. Any failed comparison resets the sequence and requires Terra review.
- Also delegate to `@security-auditor` when auth, authorization, secrets, sensitive data, external LLM calls, backup/export/import, storage, logging, caching, dependencies, validation, CORS/CSRF, error handling, cryptography, CI/CD, or other security-relevant attack surfaces change.
- Also delegate to `@uxreview` for every UI, navigation, routing, state-flow, layout, accessibility, or usability change as defined in section 12; `@uxreview` does not run tests and closes the UX-/A11y-Review-Gate itself from code, scenario alignment, and test-runner evidence.
- Independent standard, security, and UX reviews stay independent; they may run in parallel and they do not merge their ownership.
- For Java changes, `@review` on Terra evaluates the Sonar result and does not rerun Sonar itself.
- A code fix after a build or test failure must re-run all affected review gates before the next build/test attempt. If the fix touches UI, security, or shared behavior, re-run the corresponding standard, security, and UX gates again.
- Review agents report findings with severity, location, reproduction or rationale, and acceptance criteria; they do not implement fixes.
- Every finding is tracked by a finding fingerprint and a root-cause ID. Increment `confirmed-failure-count` only after a material fix and a confirmed rerun of the same gate fails again for the same root cause. Gate-close is a separate green/blocked completion record and does not itself increase failure counts; retests without remediation, a new root cause, infrastructure blockers, and approved S2/S3 exceptions do not increment counters.
- Route each finding to the task's owning `@dev1`, `@dev2`, or `@dev3`. A finding that affects a non-divisible complex task goes to `@seniordev`.
- Track all loops under the canonical `<task-id>/<root-cause-id>/<transition>/<gate>` key. `transition` is exactly `preflight`, `review:standard`, `review:security`, `review:ux`, `build`, `test-plan`, `test-run`, or `test-gate`; `gate` is its concrete owner (for example `@review-preflight`, `@review`, `@security-auditor`, `@uxreview`, `@build`, `@test-runner`, or `@testmanager`). `@review-preflight` owns the Preflight-Gate. A review failure is an open blocking finding after re-review by that same independent gate; an approved, documented S2/S3 exception under 16.5 is not a failure. A reviewer that cannot start is an infrastructure blocker, not a review failure. Keep separate counters and failure history for each gate.
- If the active key for the same gate and root cause fails more than three times (that is, on the fourth failed attempt), delegate the remaining fixes and re-reviews for that gate to `@seniordev` until all applicable reviews are green. Independent reviewers stay independent; `@seniordev` only takes over implementation and review coordination for that root cause, not reviewer ownership.
- Green review gate definition: a gate is green only when all findings from that gate are fixed and the same independent reviewer gate has re-reviewed and closed them, or when a documented exception is approved under 16.5. S0/S1 findings must never remain open at acceptance.

### 3. Build gate and build-fix loop
- Once the applicable review gates are green, delegate the local container build to `@build`, the built-in OpenCode agent (not a project file), using `.opencode/skills/docker-build-and-stack/SKILL.md`.
- `@build` remains the independent build gate. A green build requires exit code `0`.
- Treat build failures as findings and classify them first: reproducible code defect vs. infrastructure blocker.
- Reproducible code defects go to the owning implementation agent. Infrastructure blockers are handled separately and must not be miscounted as code-fix loops.
- Container, Compose, and Dockerfile cross-cutting failures are owned by `@seniordev` when they span multiple services, shared images, or host/container contract changes; isolated per-service manifest issues stay with the service owner.
- Track build loops under `<task-id>/<root-cause-id>/build/@build` with separate history from reviews and tests. One loop is one failed build gate followed by its delegated fix and rebuild.
- If the active build key fails more than three times, delegate the remaining build remediation to `@seniordev`; the build gate itself still stays `@build`.
- For pure documentation-only changes with no runtime- or configuration-impact, the build may be marked not applicable; record the reason in the handover.

### 4. Test gate and fix loop
- After a successful required build, delegate scenario-derived verification exclusively to `@testmanager`.
- `@testmanager` derives acceptance criteria, positive/negative/edge cases, selects existing regression tests, identifies missing tests, and creates the risk-based test order.
- `@test-runner` executes the ordered preflight and final suites exactly as assigned and reports evidence.
- `@test-runner` may use existing isolated local test skills/wrappers or documented local non-production test stacks only when their execution is explicitly approved via `bash: ask`; no production, LAN, or external endpoints. Standard-stack verification requires an explicit release, documented loopback + non-production confirmation, and no fixture seeding or destructive lifecycle command; otherwise switch to the isolated stack or mark the run `blocked`. Infrastructure without a skill is limited to the single validation command explicitly released in the handover and only after checking the local/non-production target environment.
- Test matrix (deterministic minimum suites):
  - Backend code incl. API/DB/Import/Rules -> full backend integration tests, executed by `@test-runner`.
  - Frontend UI/State/Router -> all `relevant` Vitest and Playwright E2E cases: every changed scenario section and journey, including affected error and edge cases; execution by `@test-runner` via `frontend-vitest-tests` and, by default, `frontend-e2e-tests` on the isolated stack. `frontend-running-stack-e2e-tests`, smoke checks, or any other standard-stack test are only allowed after explicit release plus documented confirmation of loopback/non-production and only when no fixture seeding or destructive lifecycle command is needed; otherwise fall back to the isolated stack or mark the run `blocked`.
  - API-client changes without visible UI -> treat as Frontend-State/API-Integration; `@test-runner` runs at least unit tests plus the affected E2E or contract flow.
  - Full-stack/API change with UI impact -> both of the above, all executed by `@test-runner`.
  - Security-/hardening change -> backend integration tests, Vitest, E2E, and automated A11y checks, all executed by `@test-runner`.
  - Container/Compose/Dockerfile -> Docker build plus the matching stack or smoke check from the docker/stack skill, executed by `@test-runner`.
  - CI/CD or scripts -> syntax/config validation plus the affected runtime check or smoke test, executed by `@test-runner`.
  - Automated A11y checks -> `@test-runner` runs `axe-core/playwright` or an equivalent check; `@uxreview` uses the evidence to close the UX-/A11y-Review-Gate.
  - Infrastructure changes without a suitable existing skill -> name the exact validation command and the reason in the handover; only the explicitly released command may run.
  - Pure documentation -> no runtime suite; document the reason in the handover. A build may also be marked not applicable only when there is no runtime- or configuration-impact, with the reason recorded in the handover.
  - Scenario/skill requirements may add more scope.
- `@test-runner` must report environment, scenario cases, executed skills or commands, exit codes, result, failure details, A11y evidence where relevant, and how to reproduce the error; `@uxreview` consumes that evidence and closes the UX-/A11y-Review-Gate itself, while `@testmanager` uses it for the Test-Gate decision.
- For test-infrastructure blockers, `@test-runner` classifies and documents the environment and error. The primary implementation agent delegates diagnosis to the responsible infrastructure or service owner; for cross-cutting issues, delegate to `@seniordev`. After verified remediation, retry once; do not increment the failure counter for the blocker. Classify only this retry: if it reveals a product or test failure, route it through the normal fix -> reviews -> build -> test loop; if it is again an infrastructure blocker, mark the gate `blocked` in the handover with an owner and next action and do not repeat it without evidence of further remediation.
- Test execution is allowed only in isolated local test environments or documented test stacks; never against production or LAN/external endpoints. Standard-stack checks stay blocked unless loopback + non-production are documented and the run needs no fixture seeding or destructive lifecycle commands.
- Destructive Compose, fixture, or lifecycle commands require explicit approval and must never run against the standard stack.
- A test gate is green only when the required suites ran, the relevant exit codes are `0`, the evidence matches the acceptance criteria, and `@testmanager` approves the gate.
- A test failure goes to the owning implementation agent for a fix.
- Track test planning, execution, and test-gate loops under `<task-id>/<root-cause-id>/test-plan/@testmanager`, `<task-id>/<root-cause-id>/test-run/@test-runner`, and `<task-id>/<root-cause-id>/test-gate/@testmanager` with separate history from reviews and builds. One loop is one failed test gate followed by its delegated fix and re-test; infrastructure failures do not increment the counter.
- If the active test key fails more than three times, `@seniordev` owns the remaining fixes and retest coordination for that root-cause scope only until the required build and tests are green. `@test-runner` remains the sole executor of test runs, and `@testmanager` remains the sole Test-Gate owner. For subsequent independent work, resume delegation to `@dev1`, `@dev2`, or `@dev3`.
- After a test fix, repeat this order explicitly: fix -> rerun the affected independent reviews -> perform the required rebuild -> retest with `@test-runner` -> re-evaluate the gate with `@testmanager`.

### 4.1 Mandatory handover template
- Every test-related handover must use the following Markdown sections, in this order:
- The sections may be created before execution begins. Until the owning agent writes evidence, `test-run` may stay `pending` or `not started`, and `test-gate` may stay `pending` or `not started`.

```md
## test-plan
- Scenario / feature:
- Test scope:
- Acceptance criteria:
- Regression selection:
- Risks / edge cases:
- Owner:
- Next action:

## test-run
- Status: not started | pending | pass | fail | blocked
- Scenario cases:
- Skills / commands:
- Environment (redacted):
- Artifacts (redacted refs/paths):
- Exit codes:
- Reproduction:
- Result: pass | fail | blocked
- Notes / evidence links:

## test-gate
- Status: not started | pending | green | fail | blocked
- Green criteria:
- Evidence references:
- Gaps / blockers:
- Blocker classification: product/test failure | infrastructure blocker
- Owner:
- Next action:
- Decision: green | fail | blocked
```
- `test-plan` is owned by `@testmanager` and must include the regression selection.
- `test-run` is owned by `@test-runner` and must include scenario cases, skills/commands, redacted environment and artifact references, exit codes, reproduction, and pass/fail/blocked. Before execution it may remain `pending` or `not started`.
- `test-gate` is owned by `@testmanager` and must include the green criteria, evidence references, gaps, blocker classification, owner, and next action. Before the gate is evaluated it may remain `pending` or `not started`.
- `test-gate` decisions use `green | fail | blocked`; `fail` covers product/test failures, `blocked` covers infrastructure only.
- Keep the three sections together so later sessions can read the full test lifecycle without searching.

### ux-gate handover
- Every UI-affected task or observed UI issue must also carry a dedicated `ux-gate` handover.
- `@uxreview` is the sole owner and sole closer of the UX-/A11y-Review-Gate.
- Required `ux-gate` fields: status `green | fail | blocked`, runner evidence references, open UX findings, owner, and next action.
- `blocked` is reserved for infrastructure/environment blockers; product or test findings remain `fail` until fixed and re-reviewed.

### 5. Gate status, completion, and exceptions
- Record a pass/fail summary for every task: reviews, build, tests, and outstanding blockers.
- A task is complete only when code, scenario docs, applicable review outputs, local container build, and required tests are aligned and green.
- Completion requires: build/test exit code `0`, all mandatory suites executed, no unresolved blocking findings, no open S0/S1 issues, and for Java tasks a matching green Sonar result for the final code state.
- S0 and S1 findings block completion and are never acceptable while open.
- Exceptions are allowed only for S2/S3 findings, never for S0/S1, and only with explicit written approval in a PR or Issue by a traceable, authorized repository-maintainer identity; chat approvals are not valid. Record in the handover the reference link, maintainer identity, gate, risk, reason, a binding expiry date, and, where applicable, follow-up action with owner and due date. At completion, explicitly verify and record the expiry date and any required revalidation; once the expiry is reached, the gate blocks again.
- Escalation readiness check before senior handoff: reproducible, same root cause, material fix in place, no open infrastructure classification, and all cheaper local options exhausted. If any condition is missing, mark `needs-triage` and keep the task with the current owner.
- Senior escalation scope must be exactly `<task-id>/<root-cause-id>/<transition>`; independent tasks stay with the dev owners.
- Critical escalates immediately for confirmed S0/S1, data integrity/loss, migrations or irreversible compatibility, and service-spanning contracts.
- High escalates after one confirmed failed attempt for the same root cause in two gates or an unbreakable architecture boundary.
- Normal escalation remains the fourth confirmed failure of the same root cause.
- Return to the dev owner only after the affected gates are green and there are no critical/high findings open.
- Prefer the lower-cost `@dev1`, `@dev2`, and `@dev3` for decomposable work. Use `@seniordev` for unbreakable complexity or the defined escalation only, then return to lower-cost agents for new work.
- Before delegating, verify that the named agent is available. If an agent cannot start because of configuration or infrastructure, repair that blocker or report it as blocked; do not silently omit a mandatory gate.

### 6. Task handover, loop storage, and transition schema
- Store loop counters, ownership, gate results, blockers, and any inapplicable-stage justification in the task handover so parallel agents and later sessions can continue deterministically.
- Canonical handover location and format: `.opencode/handover/<task-id>.md` as a Markdown file.
- Ownership: the primary implementation agent is the sole updater; subagents contribute only handover deltas; updates are applied sequentially per transition. During an escalation, `@seniordev` is the sole handover maintainer for the affected root-cause/transition scope only until completion. New independent tasks return to the primary or lower-cost implementation agent.
- Minimum handover schema: task id, owner, status, current transition, gate type, attempt count, confirmed-failure-count, blocker class, finding severity, finding fingerprint, root-cause ID, risk class, impact matrix, deep-gate-route, sonar status, escalation readiness, escalation trigger, escalation scope, return criteria, reviewer/gate owner, next action, responsible approver if an exception exists, and a short reason for any skipped runtime suite.
- The shadow register uses fixed columns for diff reference, risk class, path/scope, findings, fingerprint, root-cause ID, severity, deep-gate route, Luna/Terra parity, time/token cost, and maintainer approval.
- Root-Cause-ID format is `RC-<task-id>-<stable-slug>` (ASCII lower-case letters, digits, hyphens). Reuse it for every rerun of the same causal defect; create a new ID only when the causal defect changes, never merely because the symptom, file, or gate changes.
- Mark findings as one of: independent implementation finding, infrastructure blocker, or transition-specific blocker.
- Every task must state whether a transition is still active, handed over, escalated to `@seniordev`, or complete.
- Track loop keys only under the canonical transition set: `preflight|review:standard|review:security|review:ux|build|test-plan|test-run|test-gate`; do not create `review` or `test` umbrella keys. The preflight key is separate and only routes to the matching deep gates.

### 7. Security escalation rules
- Security-triggered work includes security configuration, validation, CORS/CSRF, error handling, cryptography, CI/CD, auth, authorization, secrets, sensitive data, storage, logging, caching, dependencies, and external LLM call paths.
- S0/S1 security findings must be escalated immediately to `@security-auditor` for triage together with a root-cause analysis; senior escalation only follows confirmed severity.
- If a prompt or task brief would require secrets or unnecessary sensitive data, stop and redact or split the task instead of passing them along.

### 8. UI and accessibility gates
- UI gates apply to UI changes and are scenario-driven; they must cover keyboard operation, focus order, zoom, responsive behavior, and suitable automated accessibility checks. The E2E run via `frontend-e2e-tests` or `frontend-running-stack-e2e-tests` is mandatory and must be executed by `@test-runner`. Automated accessibility checks must use `@axe-core/playwright` or an equivalent check and are likewise executed by `@test-runner`; if neither exists, the UI change must add one together with a representative authenticated Playwright test, otherwise the UX gate is blocked. `@uxreview` reviews the resulting evidence and closes the UX-/A11y-Review-Gate itself; `@testmanager` makes only the Test-Gate decision.
- Use the mandatory UI review format from section 12.
- UX review reports may contain `0-10` findings; if none are found, state the explicit status `keine Findings`.
- The test-runner report must document scenario cases and evidence for keyboard operation, focus order, zoom, responsive behavior, and the accessibility-check result.
- A UI change is not complete until the relevant UX gate is green and the scenario behavior is verified.
