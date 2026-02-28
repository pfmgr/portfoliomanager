---
name: docker-build-and-stack
description: Build images and run the local docker compose stack
---

## When to use

Use this skill when backend/frontend image builds or local stack lifecycle commands are needed.

## Scenario alignment gate

- Before final verification builds, ensure affected `scenarios/*.md` are updated for changed/new features.

## Build images

- Preferred (build both app images):
  - `./build_docker_containers.sh`
- Backend only:
  - `services/app/backend/build-docker.sh`
- Frontend only:
  - `services/app/frontend/build-docker.sh`

## Start/stop stack

- Start (or refresh) stack:
  - `docker compose up -d --force-recreate`
- Stop stack:
  - `docker compose down`
- Full reset including DB volumes:
  - `docker compose down -v`
  - `docker compose up -d`

## Diagnostics

- `docker compose logs --tail=200 db_portfolio`
- `docker compose logs --tail=200 admin_spring`
- `docker compose logs --tail=200 admin_frontend`
