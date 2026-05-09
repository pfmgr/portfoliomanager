#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/stack-env.sh"

DB_SERVICE="${STACK_DB_SERVICE:-db_portfolio}"
DB_USER="${STACK_DB_USER:-portfolio}"
DB_NAME="${STACK_DB_NAME:-portfolio}"
TEST_DEPOT_CODE="${SAVING_PLAN_LONG_METADATA_DEPOT_CODE:-tst_long}"
TEST_DEPOT_NAME="Runtime Long Metadata Depot"
TEST_ISIN="${SAVING_PLAN_LONG_METADATA_ISIN:-ZZLONGSP0001}"
VALIDATION_ISINS=(ZZLONGSP0011 ZZLONGSP0022 ZZLONGSP0033 ZZLONGSP0044)

curl_request() {
  if [ "${STACK_ALLOW_INSECURE_TLS:-false}" = "true" ]; then
    curl -k "$@"
  else
    curl "$@"
  fi
}

db_exec() {
  docker compose --env-file "${STACK_ENV_FILE}" -f "${STACK_COMPOSE_FILE}" exec -T "${DB_SERVICE}" \
    psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" "$@"
}

cleanup() {
  if [ "${KEEP_RUNNING_STACK_SAVING_PLAN_FIXTURES:-false}" = "true" ]; then
    return 0
  fi

  db_exec <<SQL >/dev/null
begin;
update depots
set active_snapshot_id = null
where depot_code = '${TEST_DEPOT_CODE}';
delete from sparplans_history where isin in ('${TEST_ISIN}', '${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}');
delete from sparplans where isin in ('${TEST_ISIN}', '${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}');
delete from instrument_dossier_extractions
where dossier_id in (
  select dossier_id
  from instrument_dossiers
  where isin in ('${TEST_ISIN}', '${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}')
);
delete from instrument_dossiers where isin in ('${TEST_ISIN}', '${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}');
delete from knowledge_base_extractions where isin in ('${TEST_ISIN}', '${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}');
delete from instruments where isin in ('${TEST_ISIN}', '${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}');
delete from depots where depot_code = '${TEST_DEPOT_CODE}';
commit;
SQL
}

assert_status() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  local body="$4"

  if [ "${actual}" != "${expected}" ]; then
    printf '%s failed: expected HTTP %s but got %s.\n' "${label}" "${expected}" "${actual}" >&2
    printf '%s\n' "${body}" >&2
    exit 1
  fi
}

assert_text_schema() {
  local non_text
  non_text="$(db_exec -At <<'SQL'
select table_name || '.' || column_name || ':' || data_type
from information_schema.columns
where table_schema = 'public'
  and (table_name, column_name) in (
    ('instruments', 'name'),
    ('instruments', 'instrument_type'),
    ('instruments', 'asset_class'),
    ('instruments', 'sub_class'),
    ('instruments', 'layer_notes'),
    ('sparplans', 'name'),
    ('snapshot_positions', 'name'),
    ('instrument_edits', 'old_value'),
    ('instrument_edits', 'new_value'),
    ('instrument_dossiers', 'display_name')
  )
  and data_type <> 'text'
order by table_name, column_name;
SQL
)"

  if [ -n "${non_text}" ]; then
    printf 'Saving-plan long-metadata schema check failed; stack is not migrated to TEXT yet:\n%s\n' "${non_text}" >&2
    exit 1
  fi
}

post_json() {
  local payload="$1"
  local body_file status
  body_file="$(mktemp)"
  status="$(curl_request -sS -o "${body_file}" -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d "${payload}" \
    "${API_BASE_URL}/sparplans")"
  printf '%s\n%s\n' "${status}" "$(cat "${body_file}")"
  rm -f "${body_file}"
}

assert_validation_rejected() {
  local label="$1"
  local payload="$2"
  local result status body
  result="$(post_json "${payload}")"
  status="$(printf '%s\n' "${result}" | sed -n '1p')"
  body="$(printf '%s\n' "${result}" | sed '1d')"

  assert_status "${label}" 400 "${status}" "${body}"
}

"${SCRIPT_DIR}/check-running-stack-ready.sh" >/dev/null
trap cleanup EXIT
cleanup
assert_text_schema

db_exec <<SQL >/dev/null
begin;
insert into depots (depot_code, name, provider)
values ('${TEST_DEPOT_CODE}', '${TEST_DEPOT_NAME}', 'TR')
on conflict (depot_code) do update
set name = excluded.name,
    provider = excluded.provider;

insert into instrument_dossiers (
  isin,
  display_name,
  created_by,
  origin,
  status,
  content_md,
  citations_json,
  content_hash,
  created_at,
  updated_at,
  version,
  authored_by,
  auto_approved
)
values (
  '${TEST_ISIN}',
  repeat('Runtime Long Display Name ', 14),
  'runtime-smoke',
  'USER',
  'DRAFT',
  'Name: Runtime long metadata instrument',
  '[]'::jsonb,
  'runtime-long-${TEST_ISIN}',
  now(),
  now(),
  1,
  'USER',
  false
);

insert into instrument_dossier_extractions (
  dossier_id,
  model,
  extracted_json,
  missing_fields_json,
  warnings_json,
  status,
  created_at,
  approved_by,
  approved_at,
  auto_approved
)
select
  dossier_id,
  'runtime-smoke',
  jsonb_build_object(
    'isin', '${TEST_ISIN}',
    'name', repeat('Runtime Long Instrument Name ', 14),
    'instrument_type', repeat('Runtime Instrument Type ', 8),
    'asset_class', repeat('Runtime Asset Class ', 9),
    'sub_class', repeat('Runtime Sub Class ', 9),
    'layer', 2,
    'layer_notes', repeat('Runtime Layer Notes ', 35)
  ),
  '[]'::jsonb,
  '[]'::jsonb,
  'APPROVED',
  now(),
  'runtime-smoke',
  now(),
  false
from instrument_dossiers
where isin = '${TEST_ISIN}';

insert into instruments (isin, name, depot_code, layer, is_deleted)
values
  ('${VALIDATION_ISINS[0]}', 'Validation Instrument 0', '${TEST_DEPOT_CODE}', 2, false),
  ('${VALIDATION_ISINS[1]}', 'Validation Instrument 32', '${TEST_DEPOT_CODE}', 2, false),
  ('${VALIDATION_ISINS[2]}', 'Validation Instrument Frequency', '${TEST_DEPOT_CODE}', 2, false),
  ('${VALIDATION_ISINS[3]}', 'Validation Instrument Amount', '${TEST_DEPOT_CODE}', 2, false);
commit;
SQL

token="$(${SCRIPT_DIR}/auth-login.sh)"
depot_id="$(db_exec -Atc "select depot_id from depots where depot_code = '${TEST_DEPOT_CODE}'")"

positive_payload="$(DEPOT_ID="${depot_id}" TEST_ISIN="${TEST_ISIN}" node -e 'process.stdout.write(JSON.stringify({depotId:Number(process.env.DEPOT_ID),isin:process.env.TEST_ISIN,amountEur:25.00,frequency:"monthly",dayOfMonth:15}))')"
positive_result="$(post_json "${positive_payload}")"
positive_status="$(printf '%s\n' "${positive_result}" | sed -n '1p')"
positive_body="$(printf '%s\n' "${positive_result}" | sed '1d')"
assert_status "long metadata create" 200 "${positive_status}" "${positive_body}"

printf '%s' "${positive_body}" | TEST_ISIN="${TEST_ISIN}" node -e '
let body = "";
process.stdin.on("data", chunk => body += chunk);
process.stdin.on("end", () => {
  const parsed = JSON.parse(body);
  if (parsed.isin !== process.env.TEST_ISIN || parsed.layer !== 2) {
    process.exit(1);
  }
});
' || {
  printf 'Long metadata create response did not include expected ISIN/layer.\n%s\n' "${positive_body}" >&2
  exit 1
}

lengths="$(db_exec -Atc "select length(name), length(instrument_type), length(asset_class), length(sub_class), length(layer_notes) from instruments where isin = '${TEST_ISIN}'")"
if ! printf '%s' "${lengths}" | awk -F '|' '{ exit !($1 > 255 && $2 > 128 && $3 > 128 && $4 > 128 && $5 > 512) }'; then
  printf 'Long metadata was not preserved beyond old varchar limits. Lengths: %s\n' "${lengths}" >&2
  exit 1
fi

list_body_file="$(mktemp)"
list_status="$(curl_request -sS -o "${list_body_file}" -w "%{http_code}" -H "Authorization: Bearer ${token}" "${API_BASE_URL}/sparplans")"
list_body="$(cat "${list_body_file}")"
rm -f "${list_body_file}"
assert_status "saving plan read-back" 200 "${list_status}" "${list_body}"
printf '%s' "${list_body}" | TEST_ISIN="${TEST_ISIN}" node -e '
let body = "";
process.stdin.on("data", chunk => body += chunk);
process.stdin.on("end", () => {
  const rows = JSON.parse(body);
  if (!Array.isArray(rows) || !rows.some(row => row.isin === process.env.TEST_ISIN)) {
    process.exit(1);
  }
});
' || {
  printf 'Created saving plan was not visible in read-back response.\n' >&2
  exit 1
}

assert_validation_rejected "dayOfMonth 0 validation" "$(DEPOT_ID="${depot_id}" ISIN="${VALIDATION_ISINS[0]}" node -e 'process.stdout.write(JSON.stringify({depotId:Number(process.env.DEPOT_ID),isin:process.env.ISIN,amountEur:25.00,dayOfMonth:0}))')"
assert_validation_rejected "dayOfMonth 32 validation" "$(DEPOT_ID="${depot_id}" ISIN="${VALIDATION_ISINS[1]}" node -e 'process.stdout.write(JSON.stringify({depotId:Number(process.env.DEPOT_ID),isin:process.env.ISIN,amountEur:25.00,dayOfMonth:32}))')"
assert_validation_rejected "frequency length validation" "$(DEPOT_ID="${depot_id}" ISIN="${VALIDATION_ISINS[2]}" node -e 'process.stdout.write(JSON.stringify({depotId:Number(process.env.DEPOT_ID),isin:process.env.ISIN,amountEur:25.00,frequency:"123456789012345678901234567890123"}))')"
assert_validation_rejected "amount precision validation" "$(DEPOT_ID="${depot_id}" ISIN="${VALIDATION_ISINS[3]}" node -e 'process.stdout.write(JSON.stringify({depotId:Number(process.env.DEPOT_ID),isin:process.env.ISIN,amountEur:1.234}))')"

created_validation_rows="$(db_exec -Atc "select count(*) from sparplans where isin in ('${VALIDATION_ISINS[0]}', '${VALIDATION_ISINS[1]}', '${VALIDATION_ISINS[2]}', '${VALIDATION_ISINS[3]}')")"
if [ "${created_validation_rows}" != "0" ]; then
  printf 'Validation cases persisted %s unexpected saving plan row(s).\n' "${created_validation_rows}" >&2
  exit 1
fi

printf 'Saving-plan long-metadata smoke checks passed.\n'
printf 'Created and read back %s with instrument field lengths %s.\n' "${TEST_ISIN}" "${lengths}"
