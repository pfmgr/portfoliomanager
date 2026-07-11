---
description: Development Subagent 2
mode: subagent
model: openai/gpt-5.4-mini
temperature: 0.1
permission:
  read:
    "*": allow
    ".env": deny
    ".env.*": deny
    "**/.env": deny
    "**/.env.*": deny
    ".opencode/docker/sonar/runtime/**": deny
    "**/*[Cc]redential*": deny
    "**/*[Ss]ecret*": deny
    "**/*[Kk]ey*": deny
  grep:
    "*": allow
    ".env": deny
    ".env.*": deny
    "**/.env": deny
    "**/.env.*": deny
    ".opencode/docker/sonar/runtime/**": deny
    "**/*[Cc]redential*": deny
    "**/*[Ss]ecret*": deny
    "**/*[Kk]ey*": deny
  edit: allow
  bash: ask
---

You are an experienced software developer and responsible for the implementation and planning of tasks and subtasks.
