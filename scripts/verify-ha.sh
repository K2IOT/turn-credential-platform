#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() {
  echo "HA verification failed: $*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "missing required file: $1"
}

require_contains() {
  local file="$1"
  local pattern="$2"
  grep -Eq -- "$pattern" "$file" || fail "$file does not match required pattern: $pattern"
}

require_not_contains() {
  local file="$1"
  local pattern="$2"
  if grep -Eq -- "$pattern" "$file"; then
    fail "$file contains forbidden pattern: $pattern"
  fi
}

verify_static_contract() {
  require_file docker-compose.prod.yml
  require_file patroni/Dockerfile
  require_file patroni/patroni.yml
  require_file patroni/entrypoint.sh
  require_file patroni/post-bootstrap.sh
  require_file haproxy/haproxy.cfg
  require_file haproxy/api-haproxy.cfg
  require_file coturn/turnserver.prod.conf

  require_contains patroni/Dockerfile 'FROM[[:space:]]+postgres:16-bookworm'
  require_contains patroni/Dockerfile 'ARG[[:space:]]+PATRONI_VERSION=4\.1\.4'
  require_contains patroni/Dockerfile 'patroni\[etcd3\]==\$\{PATRONI_VERSION\}'
  require_not_contains patroni/patroni.yml '\$\{NODE_(NAME|IP)\}'
  require_contains patroni/patroni.yml 'synchronous_mode:[[:space:]]*(true|on)'
  require_contains patroni/patroni.yml 'synchronous_node_count:[[:space:]]*1'
  require_contains patroni/patroni.yml 'use_pg_rewind:[[:space:]]*true'

  for service in etcd1 etcd2 etcd3 pg1 pg2 pg3 app1 app2 app3 db-haproxy api-haproxy redis coturn; do
    require_contains docker-compose.prod.yml "^[[:space:]]{2}${service}:"
  done

  for node in pg1 pg2 pg3; do
    require_contains docker-compose.prod.yml "PATRONI_NAME:[[:space:]]*${node}"
    require_contains haproxy/haproxy.cfg "server[[:space:]]+${node}[[:space:]]+${node}:5432"
  done

  require_contains docker-compose.prod.yml 'PATRONI_ETCD3_HOSTS:'
  require_contains docker-compose.prod.yml 'PATRONI_POSTGRESQL_CONNECT_ADDRESS:'
  require_contains docker-compose.prod.yml 'PATRONI_RESTAPI_CONNECT_ADDRESS:'
  require_contains haproxy/haproxy.cfg 'option[[:space:]]+httpchk[[:space:]]+GET[[:space:]]+/primary'
  require_contains haproxy/haproxy.cfg 'check[[:space:]]+port[[:space:]]+8008'

  for app in app1 app2 app3; do
    require_contains haproxy/api-haproxy.cfg "server[[:space:]]+${app}[[:space:]]+${app}:8080"
  done

  require_contains haproxy/api-haproxy.cfg 'option[[:space:]]+httpchk[[:space:]]+GET[[:space:]]+/actuator/health'
  require_contains docker-compose.prod.yml '"8080:8080"'
  require_contains coturn/turnserver.prod.conf 'host=db-haproxy[[:space:]]+port=5000'

  local standalone_pg_count
  standalone_pg_count="$(grep -Ec '^[[:space:]]+image:[[:space:]]+postgres:16([[:space:]]|$)' docker-compose.prod.yml || true)"
  [[ "$standalone_pg_count" -eq 0 ]] || fail "production pg services must be Patroni-managed, found standalone postgres:16 image"

  echo "Static HA topology contract: PASS"
}

if [[ "${1:-}" == "--static-only" ]]; then
  verify_static_contract
  exit 0
fi

verify_static_contract
fail "runtime HA verification not implemented yet"
