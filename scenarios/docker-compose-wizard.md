# Scenario: Docker Compose Wizard

## Purpose

- The CLI wizard prepares a local Docker Compose setup for Portfolio Manager, optionally generating a self-signed TLS certificate with SANs or wiring in third-party certificate files.
- The wizard is standalone: copying `configure-docker-compose.sh` into an empty directory is enough to generate the necessary Docker Compose, env, override, TLS config, and certificate files.
- Verification skill: `scenario-lifecycle` - keep this wizard flow, its test hooks, and runtime setup docs aligned when the compose bootstrap changes.

## Preconditions

- Local Docker/Compose is available.
- `openssl` is available when self-signed TLS is selected.
- The target directory has a writable `.local/` path for generated override files, TLS config, and certificates.
- Wizard verification should use dedicated temp paths or backup/restore to avoid overwriting a user's real `.env` or `.local/*` files.
- Verification skills: a future dedicated wizard skill for safe setup checks, plus `docker-build-and-stack` if the generated setup must be started.

## Minimal fixture set

- No existing `.env` and no existing `.local/docker-compose.override.yml` for a fresh-run path.
- A pre-existing `.env` and `.local/ssl/` for overwrite-protection and restore tests.
- Optional temporary third-party certificate and key files for external-cert mode.

## Core behavior

- The wizard writes a local `.env` file.
- The wizard writes `docker-compose.yml` when it is missing, so the generated setup does not depend on files from the source repository.
- Existing `.env` values from a prior local setup are read back in and upgraded instead of being discarded.
- Legacy LLM-related env values from pre-UI setups are preserved in the generated `.env` when present.
- The wizard can generate a self-signed certificate with multiple SAN entries.
- The wizard can reference third-party certificate and key paths without copying them into tracked files.
- The wizard writes a local compose override file for optional frontend TLS and optional backend host exposure.
- The wizard writes a local Nginx TLS config file when frontend TLS is enabled.
- Generated certificates live under `.local/ssl/` or another gitignored target path.
- Backend host exposure is off by default.

## Canonical runtime flow

1. Start the wizard and choose HTTP-only, self-signed HTTPS, or third-party HTTPS.
2. If self-signed HTTPS is chosen, enter or accept SAN entries such as `localhost`, `127.0.0.1`, or real hostnames.
3. Optionally enable backend host exposure for debugging.
4. Confirm the output paths for `.env`, the local override file, and generated certs.
5. Validate that the final compose configuration reflects the selected mode and that generated files are preserved on rerun only when explicitly overwritten.

## Canonical assertions

- `.env` is created or updated only at the selected target path.
- `.local/docker-compose.override.yml` exists and contains the selected TLS and/or backend-exposure changes.
- `docker-compose.yml` exists after a fresh standalone run.
- `.local/nginx.tls.conf` exists when TLS is enabled and is mounted by the generated override.
- Self-signed mode creates `frontend.crt` and `frontend.key` under `.local/ssl/`.
- Self-signed certificates include all requested SANs.
- Third-party mode validates paths and does not copy secrets into tracked locations.
- Existing files are not silently overwritten without confirmation or explicit force.

## APIs

- None. This is a local bootstrap flow.
- Verification skill: future wizard skill - run the script in scriptable mode and verify the generated compose files.

## Data dependencies

- `.env`
- `docker-compose.override.yml`
- `.local/docker-compose.override.yml`
- `.local/ssl/`
- `.local/nginx.tls.conf`

## UI

- None.
- Stable oracles: generated file paths, SAN content, TLS enablement flags, and backend exposure flags.

## Test layer mapping

- `backend-junit-tests`: not applicable.
- `running-instance-smoke-tests`: used after the wizard-generated stack is started.
- `frontend-running-stack-e2e-tests`: used after the wizard-generated HTTPS stack is started.
- `frontend-e2e-tests`: only for isolated-stack behavior, not the wizard itself.
- future dedicated wizard skill: verify fresh-run, rerun, SAN generation, third-party cert wiring, and restore safety.

## Edge cases

- Existing `.env` must not be clobbered silently.
- Existing `.env` should be upgraded in place when rerunning the wizard against a prior local setup.
- Existing `.local/ssl/` content must be preserved or intentionally replaced only after confirmation.
- Existing generated TLS config content must be preserved or intentionally replaced only after confirmation.
- SAN lists may contain multiple DNS names and IP addresses.
- Missing `openssl` must fail cleanly in self-signed mode.
- Invalid third-party certificate or key paths must fail cleanly.
- Backend exposure must stay disabled unless explicitly selected.
- Verification skill: future wizard skill - assert restore-on-exit behavior after a failed run.

## Code map

- Wizard: `configure-docker-compose.sh`
- Root compose: `docker-compose.yml` generated by the wizard when missing
- Generated local override: `.local/docker-compose.override.yml`
- Generated frontend TLS config: `.local/nginx.tls.conf`
- Generated certs: `.local/ssl/`
