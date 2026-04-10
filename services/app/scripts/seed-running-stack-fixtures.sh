#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/stack-env.sh"

DB_SERVICE="${STACK_DB_SERVICE:-db_portfolio}"
DB_USER="${STACK_DB_USER:-portfolio}"
DB_NAME="${STACK_DB_NAME:-portfolio}"

"${SCRIPT_DIR}/reset-running-stack-fixtures.sh" >/dev/null

docker compose --env-file "${STACK_ENV_FILE}" -f "${STACK_COMPOSE_FILE}" exec -T "${DB_SERVICE}" \
  psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" <<'SQL'
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

printf 'Running-stack fixtures seeded.\n'
printf 'Assessor fixture ISINs: ZZTESTAAA001, ZZTESTBBB002\n'
printf 'Rebalancer fixture ISIN: ZZTESTRBL003\n'
