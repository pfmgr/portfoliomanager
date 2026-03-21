---
name: knowledge-base-dossier-checks
description: Verify Knowledge Base dossier detail, approval, blacklist, and dossier list behavior
---

## When to use

Use this skill when Knowledge Base dossier workflows, blacklist activation, or dossier search/filter/sort behavior change.

## Scenario-first test scope

- Start from `scenarios/knowledgebase-extraction.md` and any linked assessor/rebalancer scenarios.
- Verify both draft and effective blacklist state.
- Verify approval and auto-approval activation rules.
- Verify dossier list filtering and sorting for approval, extraction, freshness, and blacklist status.

## Runtime flow

1. Ensure the stack is running and authenticated.
2. Open Knowledge Base dossier list.
3. Create or edit a dossier blacklist change.
4. Confirm the list shows pending vs effective state correctly.
5. Approve or auto-approve the dossier and verify the blacklist becomes effective.
6. Verify list filtering and sorting for:
   - blacklist status
   - dossier approval status
   - extraction status
   - freshness status

## Suggested evidence

- UI screenshots or recorded checks from the dossier list and dossier detail.
- Matching API responses from `/api/kb/dossiers` and `/api/kb/dossiers/{isin}`.
