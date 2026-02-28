---
name: scenario-lifecycle
description: Keep scenario docs in sync with feature changes and drive test planning from scenarios
---

## When to use

Use this skill whenever a feature is changed or a new feature is introduced.

## Mandatory workflow

1. Identify impacted scenario files in `scenarios/*.md`.
2. Update existing scenarios for behavior changes.
3. Create a new scenario file if no relevant scenario exists.
4. Derive test scope from scenario sections:
   - core behavior
   - API contracts
   - data dependencies
   - edge cases
5. Run and report tests that cover the scenario changes.

## Completion rule

A feature task is complete only when implementation, tests, and scenario docs are aligned.
