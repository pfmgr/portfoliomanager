#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_NAME="portfoliomanager_e2e"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
E2E_BASE_URL="${E2E_BASE_URL:-http://127.0.0.1:18090}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/test-results-tmp}"
AUTH_HEALTH_URL="${AUTH_HEALTH_URL:-http://127.0.0.1:18089/auth/health}"
E2E_READY_TIMEOUT_SECONDS="${E2E_READY_TIMEOUT_SECONDS:-180}"

seed_runtime_fixtures() {
  docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" exec -T e2e_db_portfolio \
    psql -v ON_ERROR_STOP=1 -U portfolio -d portfolio <<'SQL'
begin;

insert into depots (depot_code, name, provider)
values
  ('tst_assr', 'Runtime Assessor Depot', 'TR'),
  ('tst_reb', 'Runtime Rebalancer Depot', 'TR');

insert into instruments (isin, name, depot_code, layer, is_deleted)
values
  ('ZZTESTAAA001', 'Runtime Core ETF', 'tst_assr', 1, false),
  ('ZZTESTBBB002', 'Runtime Bond ETF', 'tst_assr', 2, false),
  ('ZZTESTRBL003', 'Runtime Rebalancer ETF', 'tst_reb', 5, false);

insert into sparplans (depot_id, isin, amount_eur, frequency, active)
select depot_id, 'ZZTESTAAA001', 80.00, 'monthly', true
from depots
where depot_code = 'tst_assr';

insert into sparplans (depot_id, isin, amount_eur, frequency, active)
select depot_id, 'ZZTESTBBB002', 20.00, 'monthly', true
from depots
where depot_code = 'tst_assr';

insert into sparplans (depot_id, isin, amount_eur, frequency, active)
select depot_id, 'ZZTESTRBL003', 25.00, 'monthly', true
from depots
where depot_code = 'tst_reb';

insert into snapshots (depot_id, as_of_date, source, file_hash)
select depot_id, current_date, 'TR_PDF', 'runtime-assessor-fixture'
from depots
where depot_code = 'tst_assr';

insert into snapshots (depot_id, as_of_date, source, file_hash)
select depot_id, current_date, 'TR_PDF', 'runtime-rebalancer-fixture'
from depots
where depot_code = 'tst_reb';

update depots
set active_snapshot_id = (
  select snapshot_id
  from snapshots
  where file_hash = 'runtime-assessor-fixture'
)
where depot_code = 'tst_assr';

update depots
set active_snapshot_id = (
  select snapshot_id
  from snapshots
  where file_hash = 'runtime-rebalancer-fixture'
)
where depot_code = 'tst_reb';

insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency)
select snapshot_id, 'ZZTESTAAA001', 'Runtime Core ETF', 8000.00, 'EUR'
from snapshots
where file_hash = 'runtime-assessor-fixture';

insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency)
select snapshot_id, 'ZZTESTBBB002', 'Runtime Bond ETF', 2000.00, 'EUR'
from snapshots
where file_hash = 'runtime-assessor-fixture';

insert into snapshot_positions (snapshot_id, isin, name, value_eur, currency)
select snapshot_id, 'ZZTESTRBL003', 'Runtime Rebalancer ETF', 500.00, 'EUR'
from snapshots
where file_hash = 'runtime-rebalancer-fixture';

insert into knowledge_base_extractions (isin, status, extracted_json, updated_at)
values
  ('ZZTESTAAA001', 'COMPLETE', cast('{"isin":"ZZTESTAAA001","name":"Runtime Core ETF"}' as jsonb), now()),
  ('ZZTESTBBB002', 'COMPLETE', cast('{"isin":"ZZTESTBBB002","name":"Runtime Bond ETF"}' as jsonb), now()),
  ('ZZTESTRBL003', 'COMPLETE', cast('{"isin":"ZZTESTRBL003","name":"Runtime Rebalancer ETF"}' as jsonb), now());

insert into instrument_blacklists (isin, requested_scope, effective_scope, requested_updated_at, effective_updated_at)
values
  ('ZZTESTAAA001', 'SAVING_PLAN_ONLY', 'SAVING_PLAN_ONLY', now(), now()),
  ('ZZTESTRBL003', 'SAVING_PLAN_ONLY', 'SAVING_PLAN_ONLY', now(), now());

commit;
SQL
}

generate_secret() {
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
}

ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-$(generate_secret)}"
JWT_SECRET="${JWT_SECRET:-$(generate_secret)}"
JWT_JTI_HASH_SECRET="${JWT_JTI_HASH_SECRET:-$(generate_secret)}"

while [ "${JWT_SECRET}" = "${JWT_JTI_HASH_SECRET}" ]; do
  JWT_JTI_HASH_SECRET="$(generate_secret)"
done

if [ "${#JWT_SECRET}" -lt 32 ] || [ "${#JWT_JTI_HASH_SECRET}" -lt 32 ]; then
  echo "JWT secrets must be at least 32 characters." >&2
  exit 1
fi

export ADMIN_USER ADMIN_PASS JWT_SECRET JWT_JTI_HASH_SECRET AUTH_HEALTH_URL

cleanup() {
  docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" down
}

trap cleanup EXIT

docker compose -f "${COMPOSE_FILE}" -p "${STACK_NAME}" up -d --build

frontend_ready=false
backend_ready=false
for ((i=1; i<=E2E_READY_TIMEOUT_SECONDS; i++)); do
  if curl -sf "${E2E_BASE_URL}" > /dev/null; then
    frontend_ready=true
  fi
  status_code="$(curl -s -o /dev/null -w "%{http_code}" "${AUTH_HEALTH_URL}" || true)"
  if [ "${status_code}" = "200" ] || [ "${status_code}" = "204" ]; then
    backend_ready=true
  fi
  if [ "${frontend_ready}" = true ] && [ "${backend_ready}" = true ]; then
    break
  fi
  sleep 1
done

if [ "${frontend_ready}" != true ] || [ "${backend_ready}" != true ]; then
  echo "E2E stack did not become ready in time." >&2
  exit 1
fi

seed_runtime_fixtures

cd "${ROOT_DIR}"
E2E_BASE_URL="${E2E_BASE_URL}" AUTH_HEALTH_URL="${AUTH_HEALTH_URL}" ADMIN_USER="${ADMIN_USER}" ADMIN_PASS="${ADMIN_PASS}" npx --no-install playwright test --output "${OUTPUT_DIR}"
