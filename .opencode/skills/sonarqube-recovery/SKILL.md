---
name: sonarqube-recovery
description: Clean SonarQube stack and volumes for a fresh restart
---

## When to use

Use this skill when the SonarQube containers fail to start or are stuck in an unhealthy state.

## Recovery flow

1. Clean the project-scoped stack and volumes:
   - `.opencode/scripts/sonar-prune.sh --force --volumes`
2. Start the stack again:
   - `.opencode/scripts/sonar-start.sh`
3. Verify readiness:
   - The stack is ready when `/api/system/status` returns `UP`.

## Quick recovery commands

- Full clean restart:
  - `.opencode/scripts/sonar-prune.sh --force --volumes`
  - `.opencode/scripts/sonar-start.sh`
- Verify status manually:
  - `curl -s "$(grep '^SONAR_URL=' .opencode/docker/sonar/.runtime.env | cut -d= -f2-)/api/system/status"`

## Safety

- This recovery is scoped to the current repository prefix. It does not run global Docker prune commands.
