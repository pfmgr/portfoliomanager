---
name: docker-compose-wizard-checks
description: Safely verify the Docker Compose wizard without overwriting local files
---

## When to use

Use this skill when the compose bootstrap wizard, TLS generation, or local override generation changes.

## Scenario-first test scope

- Read `scenarios/docker-compose-wizard.md` first.
- Cover fresh setup, rerun safety, self-signed SAN generation, third-party cert wiring, and backend-exposure toggles.

## Safety rules

- Never run the wizard directly against a developer's real `.env` or `.local/*` unless the files are backed up first.
- Prefer a disposable temp copy of the repo.
- If you must use the real repo, back up and restore at least:
  - `.env`
  - `docker-compose.override.yml`
  - `.local/docker-compose.override.yml`
  - `.local/ssl/`
- Use `trap` to restore originals on success, failure, and interruption.

## Preferred verification workflow

1. Capture existing local files and copy them to a unique temp backup directory.
2. Run the wizard in scriptable mode with temp targets:
   - `--non-interactive`
   - `--env-file <temp/.env>`
   - `--compose-override <temp/docker-compose.override.yml>`
   - `--ssl-dir <temp/ssl>`
3. Repeat for these cases:
   - HTTP only
   - self-signed HTTPS with multiple SANs
   - third-party HTTPS with temp cert/key files
   - backend exposure disabled
   - backend exposure enabled
4. Validate generated file contents and run `docker compose config` against the effective files.
5. If the generated stack is started, follow with `running-instance-smoke-tests` and `frontend-running-stack-e2e-tests`.
6. Restore the original local files and verify the restore is byte-for-byte correct.

## Recommended checks

- Generated self-signed certificates include all requested SANs.
- Generated cert/key paths live under the configured gitignored directory.
- `.env` contains the frontend TLS flags and the self-signed/insecure flag when applicable.
- The override file only adds TLS mounts and backend ports when selected.
- Backend is not host-exposed unless explicitly enabled.
- Existing local files survive a failed run.

## Notes

- This skill complements `scenario-lifecycle` and `docker-build-and-stack`.
- Keep the verification isolated from the user's active setup whenever possible.
