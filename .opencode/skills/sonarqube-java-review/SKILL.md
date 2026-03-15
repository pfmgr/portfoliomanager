---
name: sonarqube-java-review
description: Run SonarQube Community analysis for Java code reviews
---

## When to use

Use this skill when reviewing Java code and you need static analysis findings to inform the review.

## Preconditions

- Docker and Docker Compose are available.
- `sonar-start.sh` auto-generates and persists a Sonar token in `.opencode/docker/sonar/.runtime.env`.
- Optional override: set `SONAR_TOKEN` (or `SONAR_ADMIN_TOKEN`) explicitly.

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

## Failure handling

- If the SonarQube stack does not start or becomes unhealthy, run the `sonarqube-recovery` skill and retry once.

## Cleanup

- The stack is stopped automatically after the scan unless `--keep-running` is used.
