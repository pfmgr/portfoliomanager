---
name: backend-junit-tests
description: Run backend JUnit and integration tests with Gradle and Postgres
---

## When to use

Use this skill for Spring Boot backend validation (JUnit, integration tests, coverage gate).

## Scenario-first test scope

- Always identify relevant `scenarios/*.md` before selecting tests.
- Derive backend tests from scenario core behavior, API contracts, data dependencies, and edge cases.
- If a scenario is missing or outdated for the feature, update/add the scenario in the same change.

## Preferred command

- End-to-end backend verification:
  - `services/app/backend/run-gradle-build.sh`
  - This starts the int-test DB, runs `./gradlew clean build`, then tears the DB down.

## Manual test execution

1. Start int-test DB:
   - `docker compose -f services/app/backend/int-test-env/compose.yaml up -d`
2. Run tests:
   - `GRADLE_USER_HOME=/home/user/git/portfoliomanager/services/app/backend/.gradle_home ./gradlew test`
3. Stop int-test DB:
   - `docker compose -f services/app/backend/int-test-env/compose.yaml down`

## Fallback guidance

- If local Gradle fails with sandbox/network constraints (for example wildcard IP issues), rerun:
  - `services/app/backend/run-gradle-build.sh`
- For packaging-only verification (tests skipped):
  - `services/app/backend/build-docker.sh`
