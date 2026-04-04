#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/stack-env.sh"

DB_SERVICE="${STACK_DB_SERVICE:-db_portfolio}"
DB_USER="${STACK_DB_USER:-portfolio}"
DB_NAME="${STACK_DB_NAME:-portfolio}"

db_container_id="$(docker compose --env-file "${STACK_ENV_FILE}" -f "${STACK_COMPOSE_FILE}" ps -q "${DB_SERVICE}" 2>/dev/null || true)"
if [ -z "${db_container_id}" ]; then
  printf 'Database service %s is not available in the running stack.\n' "${DB_SERVICE}" >&2
  exit 1
fi

docker compose --env-file "${STACK_ENV_FILE}" -f "${STACK_COMPOSE_FILE}" exec -T "${DB_SERVICE}" \
  psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" <<'SQL'
begin;

update depots
set active_snapshot_id = null
where depot_code in ('tst_assr', 'tst_reb')
  and active_snapshot_id in (
    select snapshot_id
    from snapshots
    where file_hash in ('runtime-assessor-fixture', 'runtime-rebalancer-fixture')
  );

delete from snapshot_positions
where snapshot_id in (
  select snapshot_id
  from snapshots
  where file_hash in ('runtime-assessor-fixture', 'runtime-rebalancer-fixture')
);
delete from snapshots where file_hash in ('runtime-assessor-fixture', 'runtime-rebalancer-fixture');
delete from instrument_blacklists where isin in ('ZZTESTAAA001', 'ZZTESTBBB002', 'ZZTESTRBL003');
delete from knowledge_base_extractions where isin in ('ZZTESTAAA001', 'ZZTESTBBB002', 'ZZTESTRBL003');
delete from sparplans_history where isin in ('ZZTESTAAA001', 'ZZTESTBBB002', 'ZZTESTRBL003');
delete from sparplans where isin in ('ZZTESTAAA001', 'ZZTESTBBB002', 'ZZTESTRBL003');
delete from instruments where isin in ('ZZTESTAAA001', 'ZZTESTBBB002', 'ZZTESTRBL003');
delete from depots where depot_code in ('tst_assr', 'tst_reb');

commit;
SQL

printf 'Running-stack fixtures removed.\n'
