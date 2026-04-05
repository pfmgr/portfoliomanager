#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${SCRIPT_DIR}"
ENV_FILE="${REPO_ROOT}/.env"
OVERRIDE_FILE="${REPO_ROOT}/.local/docker-compose.override.yml"
DEFAULT_OVERRIDE_FILE="${REPO_ROOT}/.local/docker-compose.override.yml"
ROOT_OVERRIDE_FILE="${REPO_ROOT}/docker-compose.override.yml"
SSL_DIR="${REPO_ROOT}/.local/ssl"
TLS_MODE=""
BACKEND_EXPOSED=""
FRONTEND_BIND="${ADMIN_FRONTEND_BIND:-127.0.0.1}"
FRONTEND_HTTP_PORT="${ADMIN_FRONTEND_PORT:-8080}"
FRONTEND_TLS_PORT="${ADMIN_FRONTEND_TLS_PORT:-8443}"
BACKEND_BIND="${ADMIN_SPRING_BIND:-127.0.0.1}"
BACKEND_PORT="${ADMIN_SPRING_PORT:-8089}"
SAN_NAMES="${ADMIN_FRONTEND_TLS_SAN_NAMES:-localhost,127.0.0.1}"
CERT_PATH="${ADMIN_FRONTEND_TLS_CERT_PATH:-}"
KEY_PATH="${ADMIN_FRONTEND_TLS_KEY_PATH:-}"
FORCE=false
NON_INTERACTIVE=false

usage() {
  cat <<'EOF'
Usage: ./configure-docker-compose.sh [options]

Options:
  --non-interactive        Fail instead of prompting for missing values
  --force                  Overwrite existing target files
  --env-file PATH          Write .env to PATH
  --compose-override PATH  Write local compose override to PATH
  --ssl-dir PATH           Write self-signed certs to PATH
  --mode MODE              http | self-signed | third-party
  --backend-exposed BOOL   true | false
  --frontend-bind HOST     Frontend bind host
  --frontend-http-port N   Frontend HTTP host port
  --frontend-tls-port N    Frontend HTTPS host port
  --backend-bind HOST      Backend bind host (optional exposure)
  --backend-port N         Backend host port (optional exposure)
  --sans LIST              Comma-separated SAN entries for self-signed certs
  --cert-path PATH         Third-party certificate path
  --key-path PATH          Third-party private key path
  --help                   Show this help
EOF
}

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

prompt() {
  local text="$1"
  local default_value="${2:-}"
  local reply

  if [ -n "${default_value}" ]; then
    printf '%s [%s]: ' "${text}" "${default_value}" >&2
  else
    printf '%s: ' "${text}" >&2
  fi

  IFS= read -r reply
  if [ -z "${reply}" ]; then
    printf '%s' "${default_value}"
  else
    printf '%s' "${reply}"
  fi
}

prompt_yes_no() {
  local text="$1"
  local default_value="${2:-false}"
  local default_hint="y/N"
  local reply

  if [ "${default_value}" = "true" ]; then
    default_hint="Y/n"
  fi

  while true; do
    printf '%s [%s]: ' "${text}" "${default_hint}" >&2
    IFS= read -r reply
    case "${reply:-${default_value}}" in
      y|Y|yes|YES|true|TRUE)
        printf 'true'
        return
        ;;
      n|N|no|NO|false|FALSE)
        printf 'false'
        return
        ;;
      "")
        printf '%s' "${default_value}"
        return
        ;;
      *)
        printf 'Please answer yes or no.\n' >&2
        ;;
    esac
  done
}

trim() {
  local value="$1"
  value="${value#${value%%[![:space:]]*}}"
  value="${value%${value##*[![:space:]]}}"
  printf '%s' "${value}"
}

resolve_abs_path() {
  local input_path="$1"
  if [ -z "${input_path}" ]; then
    printf '%s' ""
    return
  fi

  if [ -d "${input_path}" ]; then
    (cd "${input_path}" && pwd -P)
    return
  fi

  local dir base
  dir="$(dirname -- "${input_path}")"
  base="$(basename -- "${input_path}")"
  (cd "${dir}" && printf '%s/%s' "$(pwd -P)" "${base}")
}

ensure_parent_dir() {
  local target="$1"
  mkdir -p "$(dirname -- "${target}")"
}

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

quote_env_value() {
  local value="$1"
  value="${value//\'/\'\"\'\"\'}"
  printf "'%s'" "${value}"
}

write_atomic() {
  local target="$1"
  local content="$2"
  local tmp
  ensure_parent_dir "${target}"
  tmp="$(mktemp "$(dirname -- "${target}")/.wizard.XXXXXX")"
  printf '%s' "${content}" > "${tmp}"
  mv -- "${tmp}" "${target}"
}

is_ipv4() {
  [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
}

is_ipv6() {
  [[ "$1" == *:* ]]
}

is_valid_san() {
  local value="$1"

  [ -n "${value}" ] || return 1
  case "${value}" in
    *[[:space:]]*|*/*|*://*)
      return 1
      ;;
  esac

  if is_ipv4 "${value}"; then
    return 0
  fi

  if [[ "${value}" =~ ^[0-9A-Fa-f:]+$ ]] && is_ipv6 "${value}"; then
    return 0
  fi

  if [[ "${value}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
    return 0
  fi

  return 1
}

normalize_san_list() {
  local san_list="$1"
  local bind_host="$2"
  local normalized=()
  local seen=""
  local san item

  if [ -n "${san_list}" ]; then
    IFS=',' read -r -a san_items <<< "${san_list}"
    for san in "${san_items[@]}"; do
      item="$(trim "${san}")"
      [ -n "${item}" ] || continue
      is_valid_san "${item}" || die "Invalid SAN entry: ${item}"
      case ",${seen}," in
        *",${item},"*)
          continue
          ;;
      esac
      normalized+=("${item}")
      seen+="${item},"
    done
  fi

  if [ -n "${bind_host}" ]; then
    is_valid_san "${bind_host}" || die "Invalid frontend bind host for SANs: ${bind_host}"
    case ",${seen}," in
      *",${bind_host},"*)
        ;;
      *)
        if [ "${#normalized[@]}" -eq 0 ]; then
          normalized=("${bind_host}" "localhost" "127.0.0.1" "::1")
        else
          normalized+=("${bind_host}")
        fi
        ;;
    esac
  fi

  if [ "${#normalized[@]}" -eq 0 ]; then
    normalized=("localhost" "127.0.0.1" "::1")
  fi

  (IFS=','; printf '%s' "${normalized[*]}")
}

generate_san_config() {
  local san_list="$1"
  local tmp_config="$2"
  local entries=()
  local dns_count=0
  local ip_count=0
  local first_san=""
  local san item

  IFS=',' read -r -a san_items <<< "${san_list}"
  for san in "${san_items[@]}"; do
    item="$(trim "${san}")"
    [ -n "${item}" ] || continue
    is_valid_san "${item}" || die "Invalid SAN entry: ${item}"
    if [ -z "${first_san}" ]; then
      first_san="${item}"
    fi
    if is_ipv4 "${item}" || is_ipv6 "${item}"; then
      ip_count=$((ip_count + 1))
      entries+=("IP.${ip_count} = ${item}")
    else
      dns_count=$((dns_count + 1))
      entries+=("DNS.${dns_count} = ${item}")
    fi
  done

  [ -n "${first_san}" ] || die "At least one SAN entry is required for self-signed mode."

  cat > "${tmp_config}" <<EOF
[req]
default_bits = 4096
prompt = no
default_md = sha256
distinguished_name = req_distinguished_name
x509_extensions = req_ext

[req_distinguished_name]
CN = ${first_san}

[req_ext]
subjectAltName = @alt_names
extendedKeyUsage = serverAuth

[alt_names]
$(printf '%s\n' "${entries[@]}")
EOF

  printf '%s' "${first_san}"
}

generate_self_signed_cert() {
  local cert_path="$1"
  local key_path="$2"
  local san_list="$3"
  local work_dir="$4"
  local openssl_config="${work_dir}/openssl.cnf"
  local cn

  cn="$(generate_san_config "${san_list}" "${openssl_config}")"
  mkdir -p "$(dirname -- "${cert_path}")" "$(dirname -- "${key_path}")"
  openssl req -x509 -nodes -newkey rsa:4096 -days 825 \
    -keyout "${key_path}" \
    -out "${cert_path}" \
    -config "${openssl_config}" \
    -extensions req_ext \
    -subj "/CN=${cn}"
  chmod 600 "${key_path}"
}

compose_override_content() {
  local tls_enabled="$1"
  local backend_exposed="$2"
  local cert_path="$3"
  local key_path="$4"
  local frontend_tls_config_path="$5"
  local content=""

  if [ "${tls_enabled}" = "true" ]; then
    content+=$'services:\n'
    content+=$'  admin_frontend:\n'
    content+=$'    volumes:\n'
    content+="      - \"${frontend_tls_config_path}:/etc/nginx/conf.d/default.conf:ro\"\n"
    content+="      - \"${cert_path}:/etc/nginx/certs/frontend.crt:ro\"\n"
    content+="      - \"${key_path}:/etc/nginx/certs/frontend.key:ro\"\n"
  fi

  if [ "${backend_exposed}" = "true" ]; then
    if [ -z "${content}" ]; then
      content+=$'services:\n'
    fi
    content+=$'  admin_spring:\n'
    content+=$'    ports:\n'
    content+="      - \"${BACKEND_BIND}:${BACKEND_PORT}:8080\"\n"
  fi

  if [ -z "${content}" ]; then
    content+=$'services: {}\n'
  fi

  printf '%b' "${content}"
}

sync_root_override() {
  local source_file="$1"
  local target_file="$2"

  ln -sfn -- "${source_file}" "${target_file}" 2>/dev/null || cp -- "${source_file}" "${target_file}"
}

env_content() {
  local tls_enabled="$1"
  local self_signed="$2"
  local cert_path="$3"
  local key_path="$4"
  local san_list="$5"
  local frontend_tls_enabled="false"
  local frontend_tls_self_signed="false"
  local frontend_port="${FRONTEND_HTTP_PORT}"
  local frontend_container_port="80"
  local frontend_scheme="http"
  local insecure_tls="false"

  if [ "${tls_enabled}" = "true" ]; then
    frontend_tls_enabled="true"
    frontend_port="${FRONTEND_TLS_PORT}"
    frontend_container_port="443"
    frontend_scheme="https"
    insecure_tls="${self_signed}"
    frontend_tls_self_signed="${self_signed}"
  fi

  cat <<EOF
# Generated by configure-docker-compose.sh.

# Database
PORTFOLIO_DB_PASSWORD=$(quote_env_value "${PORTFOLIO_DB_PASSWORD:-$(generate_secret)}")

# Admin UI auth (Basic Auth + JWT)
ADMIN_USER=$(quote_env_value "${ADMIN_USER:-admin}")
ADMIN_PASS=$(quote_env_value "${ADMIN_PASS:-$(generate_secret)}")
JWT_SECRET=$(quote_env_value "${JWT_SECRET:-$(generate_secret)}")
JWT_JTI_HASH_SECRET=$(quote_env_value "${JWT_JTI_HASH_SECRET:-$(generate_secret)}")
JWT_ISSUER=$(quote_env_value "${JWT_ISSUER:-portfolio-manager}")

# Bind addresses (keep local by default)
ADMIN_SPRING_BIND=$(quote_env_value "${BACKEND_BIND}")
ADMIN_SPRING_PORT=$(quote_env_value "${BACKEND_PORT}")
ADMIN_FRONTEND_BIND=$(quote_env_value "${FRONTEND_BIND}")
ADMIN_FRONTEND_PORT=$(quote_env_value "${frontend_port}")
ADMIN_FRONTEND_CONTAINER_PORT=$(quote_env_value "${frontend_container_port}")

# Frontend TLS (optional)
ADMIN_FRONTEND_TLS_ENABLED=$(quote_env_value "${frontend_tls_enabled}")
ADMIN_FRONTEND_TLS_SELF_SIGNED=$(quote_env_value "${frontend_tls_self_signed}")
ADMIN_FRONTEND_TLS_BIND=$(quote_env_value "${FRONTEND_BIND}")
ADMIN_FRONTEND_TLS_PORT=$(quote_env_value "${FRONTEND_TLS_PORT}")
ADMIN_FRONTEND_TLS_SAN_NAMES=$(quote_env_value "${san_list}")
ADMIN_FRONTEND_TLS_CERT_PATH=$(quote_env_value "${cert_path}")
ADMIN_FRONTEND_TLS_KEY_PATH=$(quote_env_value "${key_path}")
STACK_ALLOW_INSECURE_TLS=$(quote_env_value "${insecure_tls}")

# Knowledge Base (KB)
KB_ENABLED=$(quote_env_value "${KB_ENABLED:-true}")
KB_LLM_ENABLED=$(quote_env_value "${KB_LLM_ENABLED:-false}")
LLM_CONFIG_ENCRYPTION_PASSWORD=$(quote_env_value "${LLM_CONFIG_ENCRYPTION_PASSWORD:-$(generate_secret)}")
EOF
}

if [ "$#" -gt 0 ]; then
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help)
        usage
        exit 0
        ;;
      --non-interactive)
        NON_INTERACTIVE=true
        ;;
      --force)
        FORCE=true
        ;;
      --env-file)
        shift
        ENV_FILE="${1:-}"
        ;;
      --compose-override|--compose-override-file)
        shift
        OVERRIDE_FILE="${1:-}"
        ;;
      --ssl-dir)
        shift
        SSL_DIR="${1:-}"
        ;;
      --mode)
        shift
        TLS_MODE="${1:-}"
        ;;
      --backend-exposed)
        shift
        BACKEND_EXPOSED="${1:-}"
        ;;
      --frontend-bind)
        shift
        FRONTEND_BIND="${1:-}"
        ;;
      --frontend-http-port)
        shift
        FRONTEND_HTTP_PORT="${1:-}"
        ;;
      --frontend-tls-port)
        shift
        FRONTEND_TLS_PORT="${1:-}"
        ;;
      --backend-bind)
        shift
        BACKEND_BIND="${1:-}"
        ;;
      --backend-port)
        shift
        BACKEND_PORT="${1:-}"
        ;;
      --sans)
        shift
        SAN_NAMES="${1:-}"
        ;;
      --cert-path)
        shift
        CERT_PATH="${1:-}"
        ;;
      --key-path)
        shift
        KEY_PATH="${1:-}"
        ;;
      *)
        die "Unknown option: $1"
        ;;
    esac
    shift
  done
fi

if [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
  if [ -z "${TLS_MODE}" ]; then
    printf 'Choose frontend mode:\n' >&2
    printf '  1) HTTP only\n' >&2
    printf '  2) HTTPS with self-signed certificate\n' >&2
    printf '  3) HTTPS with third-party certificate\n' >&2
    TLS_MODE="$(prompt 'Mode (1/2/3)' '1')"
    case "${TLS_MODE}" in
      1) TLS_MODE="http" ;;
      2) TLS_MODE="self-signed" ;;
      3) TLS_MODE="third-party" ;;
      http|self-signed|third-party) ;;
      *) die "Invalid mode." ;;
    esac
  fi

  if [ -z "${BACKEND_EXPOSED}" ]; then
    BACKEND_EXPOSED="$(prompt_yes_no 'Expose backend on host for debugging?' 'false')"
  fi

  FRONTEND_BIND="$(prompt 'Frontend bind host' "${FRONTEND_BIND}")"
  FRONTEND_HTTP_PORT="$(prompt 'Frontend HTTP port' "${FRONTEND_HTTP_PORT}")"
  FRONTEND_TLS_PORT="$(prompt 'Frontend HTTPS port' "${FRONTEND_TLS_PORT}")"
  BACKEND_BIND="$(prompt 'Backend bind host' "${BACKEND_BIND}")"
  BACKEND_PORT="$(prompt 'Backend port' "${BACKEND_PORT}")"
fi

if [ -z "${TLS_MODE}" ]; then
  TLS_MODE="http"
fi

case "${TLS_MODE}" in
  http)
    TLS_ENABLED=false
    SELF_SIGNED=false
    CERT_PATH=""
    KEY_PATH=""
    STACK_ALLOW_INSECURE_TLS="false"
    ;;
  self-signed)
    TLS_ENABLED=true
    SELF_SIGNED=true
    if [ -z "${SAN_NAMES}" ] && [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
      SAN_NAMES="$(prompt 'Frontend SANs (comma-separated DNS/IP entries)' "${FRONTEND_BIND},localhost,127.0.0.1,::1")"
    fi
    SAN_NAMES="$(normalize_san_list "${SAN_NAMES}" "${FRONTEND_BIND}")"
    CERT_PATH="${CERT_PATH:-${SSL_DIR}/frontend.crt}"
    KEY_PATH="${KEY_PATH:-${SSL_DIR}/frontend.key}"
    STACK_ALLOW_INSECURE_TLS="true"
    ;;
  third-party)
    TLS_ENABLED=true
    SELF_SIGNED=false
    if [ -z "${CERT_PATH}" ] && [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
      CERT_PATH="$(prompt 'Certificate path' '')"
    fi
    if [ -z "${KEY_PATH}" ] && [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
      KEY_PATH="$(prompt 'Private key path' '')"
    fi
    [ -n "${CERT_PATH}" ] || die "Certificate path is required for third-party mode."
    [ -n "${KEY_PATH}" ] || die "Private key path is required for third-party mode."
    CERT_PATH="$(resolve_abs_path "${CERT_PATH}")"
    KEY_PATH="$(resolve_abs_path "${KEY_PATH}")"
    [ -f "${CERT_PATH}" ] || die "Certificate file not found: ${CERT_PATH}"
    [ -f "${KEY_PATH}" ] || die "Private key file not found: ${KEY_PATH}"
    [ -r "${CERT_PATH}" ] || die "Certificate file is not readable: ${CERT_PATH}"
    [ -r "${KEY_PATH}" ] || die "Private key file is not readable: ${KEY_PATH}"
    ;;
  *)
    die "Unknown mode: ${TLS_MODE}"
    ;;
esac

if [ "${BACKEND_EXPOSED}" = "" ]; then
  BACKEND_EXPOSED=false
fi

CREATE_ROOT_OVERRIDE=false
if [ "${OVERRIDE_FILE}" = "${DEFAULT_OVERRIDE_FILE}" ]; then
  CREATE_ROOT_OVERRIDE=true
fi

if [ "${TLS_MODE}" = "self-signed" ]; then
  command -v openssl >/dev/null 2>&1 || die "openssl is required to generate a self-signed certificate."
fi

if [ "${TLS_ENABLED}" = "true" ]; then
  ensure_parent_dir "${CERT_PATH}"
  ensure_parent_dir "${KEY_PATH}"
fi

if [ "${TLS_MODE}" = "self-signed" ] && { [ -e "${CERT_PATH}" ] || [ -e "${KEY_PATH}" ]; } && [ "${FORCE}" = "false" ]; then
  if [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
    if [ "$(prompt_yes_no "Existing TLS cert files found at ${CERT_PATH} and/or ${KEY_PATH}. Overwrite them?" 'false')" != "true" ]; then
      die "Aborted without changing existing TLS certificate files."
    fi
  else
    die "Existing TLS certificate files found. Re-run with --force to overwrite them."
  fi
fi

if [ -e "${ENV_FILE}" ] && [ "${FORCE}" = "false" ]; then
  if [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
    if [ "$(prompt_yes_no "${ENV_FILE} exists. Overwrite it?" 'false')" != "true" ]; then
      die "Aborted without changing ${ENV_FILE}."
    fi
  else
    die "${ENV_FILE} already exists. Re-run with --force to overwrite it."
  fi
fi

if [ -e "${OVERRIDE_FILE}" ] && [ "${FORCE}" = "false" ]; then
  if [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
    if [ "$(prompt_yes_no "${OVERRIDE_FILE} exists. Overwrite it?" 'false')" != "true" ]; then
      die "Aborted without changing ${OVERRIDE_FILE}."
    fi
  else
    die "${OVERRIDE_FILE} already exists. Re-run with --force to overwrite it."
  fi
fi

if [ "${CREATE_ROOT_OVERRIDE}" = "true" ] && [ -e "${ROOT_OVERRIDE_FILE}" ] && [ "${FORCE}" = "false" ]; then
  if [ -t 0 ] && [ "${NON_INTERACTIVE}" = "false" ]; then
    if [ "$(prompt_yes_no "${ROOT_OVERRIDE_FILE} exists. Overwrite it?" 'false')" != "true" ]; then
      die "Aborted without changing ${ROOT_OVERRIDE_FILE}."
    fi
  else
    die "${ROOT_OVERRIDE_FILE} already exists. Re-run with --force to overwrite it."
  fi
fi

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${work_dir}"
}
trap cleanup EXIT

temp_cert_path="${CERT_PATH}"
temp_key_path="${KEY_PATH}"
if [ "${TLS_MODE}" = "self-signed" ]; then
  temp_cert_path="${work_dir}/frontend.crt"
  temp_key_path="${work_dir}/frontend.key"
  generate_self_signed_cert "${temp_cert_path}" "${temp_key_path}" "${SAN_NAMES}" "${work_dir}"
  mkdir -p "${SSL_DIR}"
  mv -- "${temp_cert_path}" "${CERT_PATH}"
  mv -- "${temp_key_path}" "${KEY_PATH}"
fi

frontend_tls_config_path="${REPO_ROOT}/services/app/frontend/nginx.tls.conf"
override_content="$(compose_override_content "${TLS_ENABLED}" "${BACKEND_EXPOSED}" "${CERT_PATH}" "${KEY_PATH}" "${frontend_tls_config_path}")"
env_content="$(env_content "${TLS_ENABLED}" "${SELF_SIGNED}" "${CERT_PATH}" "${KEY_PATH}" "${SAN_NAMES}")"

write_atomic "${OVERRIDE_FILE}" "${override_content}"
write_atomic "${ENV_FILE}" "${env_content}"

if [ "${CREATE_ROOT_OVERRIDE}" = "true" ]; then
  sync_root_override "${OVERRIDE_FILE}" "${ROOT_OVERRIDE_FILE}"
fi

printf 'Wrote %s\n' "${ENV_FILE}"
printf 'Wrote %s\n' "${OVERRIDE_FILE}"
if [ "${CREATE_ROOT_OVERRIDE}" = "true" ]; then
  printf 'Wrote %s\n' "${ROOT_OVERRIDE_FILE}"
fi
if [ "${TLS_MODE}" = "self-signed" ]; then
  printf 'Wrote self-signed certificate to %s and %s\n' "${CERT_PATH}" "${KEY_PATH}"
fi
printf 'Frontend URL: %s://%s:%s\n' "$([ "${TLS_ENABLED}" = "true" ] && printf 'https' || printf 'http')" "${FRONTEND_BIND}" "$([ "${TLS_ENABLED}" = "true" ] && printf '%s' "${FRONTEND_TLS_PORT}" || printf '%s' "${FRONTEND_HTTP_PORT}")"
printf 'Backend host exposure: %s\n' "${BACKEND_EXPOSED}"
