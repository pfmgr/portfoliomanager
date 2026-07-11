---
name: sonarqube-recovery
description: Recover the SonarQube stack after explicit human approval
---

## When to use

Use this skill when the SonarQube containers fail to start or are stuck in an unhealthy state.

## Recovery flow

1. Obtain and record explicit human approval before any volume deletion. Without it, leave the preflight `blocked`.
2. For a non-destructive restart, use `.opencode/scripts/sonar-prune.sh --force` (the default preserves volumes and runtime state).
3. Clean the project-scoped stack and volumes only when that approval specifically includes volumes:
   - `.opencode/scripts/sonar-prune.sh --force --volumes`
4. Start the stack again:
   - `.opencode/scripts/sonar-start.sh`
5. Verify readiness:
   - The stack is ready when `/api/system/status` returns `UP`.

## Quick recovery commands

- Approved full clean restart only:
  - `.opencode/scripts/sonar-prune.sh --force --volumes`
  - `.opencode/scripts/sonar-start.sh`
- Verify status manually using the loopback URL printed by `sonar-start.sh`; do not read or print the private runtime file.

## Safety

- This recovery is scoped to the current repository prefix. It does not run global Docker prune commands.
- Never delete volumes automatically. After approved recovery, make exactly one Sonar retry; a further infrastructure failure remains `blocked`.
