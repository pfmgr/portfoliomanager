---
name: sonarqube-java-review
description: Run SonarQube Community analysis for Java code reviews
---

## When to use

Use this skill when reviewing Java code and you need static analysis findings to inform the review.

## Preconditions

- Docker and Docker Compose are available.
- The approved pinned SonarQube, Postgres, and scanner images must already be available locally. If not, preflight is `blocked` and names the missing approved digest.
- `sonar-start.sh` securely bootstraps two project-scoped tokens when needed: an analysis token with only `scan`, and a separate read/browse token for quality-gate and issue queries. Both are private runtime files and are never printed.
- Bootstrap is local-only: the generated bootstrap password is kept in the protected runtime directory. No bootstrap-admin token environment variable is accepted or forwarded.

## Preferred command

- `.opencode/scripts/sonar-java-review.sh --project-dir <path> --project-key <key>`

## Quick smoke commands

- Standard Java review run:
  - `.opencode/scripts/sonar-java-review.sh --project-dir <java-project-path> --project-key <repo-key>`
- Keep SonarQube running for manual API/UI checks:
  - `.opencode/scripts/sonar-java-review.sh --project-dir <java-project-path> --project-key <repo-key> --keep-running`
  - `.opencode/scripts/sonar-stop.sh`

## Expected output

- SonarQube quality gate status.
- Top open issues (up to 10).
- The pre-test gate blocks new bugs, vulnerabilities, and degraded reliability/security ratings. It deliberately does **not** contain a coverage condition; coverage belongs to the later test/test-manager gate.

## Diff context

The script intentionally does not enumerate Git diffs: a generic repository scan could
accidentally include runtime credentials or expose paths through shell output. The
orchestrating agent must pass its already-sanitized diff context to the preflight report.

## Failure handling

- If the stack does not start or becomes unhealthy, report `blocked`. Do not run recovery automatically. A human may explicitly approve and execute the recovery skill; only then is one retry allowed.

## Cleanup

- The stack is stopped automatically after the scan unless `--keep-running` is used.
