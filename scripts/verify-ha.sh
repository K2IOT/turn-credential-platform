#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
APP_DB="${POSTGRES_APP_DB:-turncred}"
APP_USER="${POSTGRES_APP_USER:-turncred}"
APP_PASSWORD="${POSTGRES_APP_PASSWORD:-turncred}"
ADMIN_KEY="${TURN_ADMIN_API_KEY:-dev-admin-key}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
KEEP_STACK="${KEEP_STACK:-0}"
HA_LOG_FILE="${HA_LOG_FILE:-ha-compose.log}"

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

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

service_running() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "$service" 2>/dev/null || true)"
  [[ -n "$container_id" ]] || return 1
  [[ "$(docker inspect -f '{{.State.Running}}' "$container_id" 2>/dev/null || true)" == "true" ]]
}

service_healthy() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "$service" 2>/dev/null || true)"
  [[ -n "$container_id" ]] || return 1
  [[ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id" 2>/dev/null || true)" == "healthy" ]]
}

patroni_code() {
  local service="$1"
  local endpoint="$2"
  if ! service_running "$service"; then
    printf '000'
    return 0
  fi
  compose exec -T "$service" curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:8008${endpoint}" 2>/dev/null || printf '000'
}

current_primary() {
  local primary=""
  local node
  for node in pg1 pg2 pg3; do
    if [[ "$(patroni_code "$node" /primary)" == "200" ]]; then
      [[ -z "$primary" ]] || return 1
      primary="$node"
    fi
  done
  [[ -n "$primary" ]] || return 1
  printf '%s\n' "$primary"
}

replica_count() {
  local count=0
  local node
  for node in pg1 pg2 pg3; do
    if [[ "$(patroni_code "$node" /replica)" == "200" ]]; then
      count=$((count + 1))
    fi
  done
  printf '%s\n' "$count"
}

wait_for_cluster() {
  local attempts="${1:-90}"
  local expected_old_primary="${2:-}"
  local expected_replicas="${3:-2}"
  local i primary replicas
  for ((i=1; i<=attempts; i++)); do
    primary="$(current_primary 2>/dev/null || true)"
    replicas="$(replica_count)"
    if [[ -n "$primary" && "$replicas" -eq "$expected_replicas" ]]; then
      if [[ -z "$expected_old_primary" || "$primary" != "$expected_old_primary" ]]; then
        echo "Patroni topology ready: primary=${primary}, replicas=${replicas}/${expected_replicas}"
        printf '%s\n' "$primary"
        return 0
      fi
    fi
    echo "Waiting for Patroni topology ($i/$attempts): primary=${primary:-none}, replicas=${replicas}/${expected_replicas}"
    sleep 2
  done
  return 1
}

wait_for_old_primary_replica() {
  local service="$1"
  local i
  for ((i=1; i<=90; i++)); do
    if [[ "$(patroni_code "$service" /replica)" == "200" ]]; then
      echo "${service} rejoined as replica"
      return 0
    fi
    echo "Waiting for ${service} to rejoin as replica ($i/90)"
    sleep 2
  done
  return 1
}

running_pg_client() {
  local node
  for node in pg1 pg2 pg3; do
    if service_running "$node"; then
      printf '%s\n' "$node"
      return 0
    fi
  done
  return 1
}

psql_via_proxy() {
  local client
  client="$(running_pg_client)" || return 1
  compose exec -T "$client" env PGPASSWORD="$APP_PASSWORD" \
    psql -X -v ON_ERROR_STOP=1 -h db-haproxy -p 5000 -U "$APP_USER" -d "$APP_DB" "$@"
}

psql_on_node() {
  local node="$1"
  shift
  compose exec -T "$node" env PGPASSWORD="$APP_PASSWORD" \
    psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U "$APP_USER" -d "$APP_DB" "$@"
}

proxy_ready() {
  [[ "$(psql_via_proxy -tAc 'SELECT 1' 2>/dev/null | tr -d '[:space:]' || true)" == "1" ]]
}

wait_for_proxy() {
  local i
  for ((i=1; i<=60; i++)); do
    if proxy_ready; then
      echo "Database HAProxy is accepting SQL"
      return 0
    fi
    echo "Waiting for database HAProxy ($i/60)"
    sleep 2
  done
  return 1
}

wait_for_api() {
  local i code
  for ((i=1; i<=90; i++)); do
    code="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/health" 2>/dev/null || true)"
    if [[ "$code" == "200" ]]; then
      echo "API HAProxy is healthy"
      return 0
    fi
    echo "Waiting for API HAProxy ($i/90, status=${code:-000})"
    sleep 2
  done
  return 1
}

wait_for_apps() {
  local i
  for ((i=1; i<=90; i++)); do
    if service_healthy app1 && service_healthy app2 && service_healthy app3; then
      echo "All three application instances are healthy"
      return 0
    fi
    echo "Waiting for app1/app2/app3 health ($i/90)"
    sleep 2
  done
  return 1
}

node_has_marker() {
  local node="$1"
  local marker="$2"
  [[ "$(psql_on_node "$node" -tAc "SELECT count(*) FROM ha_probe WHERE id='${marker}'" 2>/dev/null | tr -d '[:space:]' || true)" == "1" ]]
}

wait_for_marker_on_node() {
  local node="$1"
  local marker="$2"
  local i
  for ((i=1; i<=60; i++)); do
    if node_has_marker "$node" "$marker"; then
      echo "Replication marker ${marker} visible on ${node}"
      return 0
    fi
    sleep 1
  done
  return 1
}

cleanup() {
  local status=$?
  set +e
  if [[ "$status" -ne 0 ]]; then
    echo "=== HA verification diagnostics ===" >&2
    compose ps >&2 || true
    compose logs --no-color --tail 300 >"$HA_LOG_FILE" 2>&1 || true
    cat "$HA_LOG_FILE" >&2 || true
  fi
  if [[ "$KEEP_STACK" != "1" ]]; then
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$status"
}

verify_static_contract
compose config -q

trap cleanup EXIT
rm -f "$HA_LOG_FILE"

echo "Building application JAR..."
./mvnw -B clean package -DskipTests

echo "Starting clean production HA topology..."
compose down -v --remove-orphans >/dev/null 2>&1 || true
compose up -d --build

initial_primary="$(wait_for_cluster)" || fail "Patroni cluster did not reach one-primary/two-replica topology"
initial_primary="$(printf '%s\n' "$initial_primary" | tail -n 1)"
wait_for_proxy || fail "database HAProxy never became ready"
wait_for_apps || fail "all three application instances did not become healthy"
wait_for_api || fail "API HAProxy never became ready"

marker="before-failover-$(date +%s%N)"
psql_via_proxy -c 'CREATE TABLE IF NOT EXISTS ha_probe (id text PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now())'
psql_via_proxy -c "INSERT INTO ha_probe(id) VALUES ('${marker}')"

for node in pg1 pg2 pg3; do
  if [[ "$node" != "$initial_primary" ]]; then
    wait_for_marker_on_node "$node" "$marker" || fail "marker did not replicate to ${node}"
  fi
done

echo "Running existing application E2E suite through API HAProxy..."
SKIP_BUILD=1 SKIP_COMPOSE_UP=1 COMPOSE_FILE="$COMPOSE_FILE" ADMIN_KEY="$ADMIN_KEY" BASE_URL="$BASE_URL" \
  bash scripts/verify-e2e.sh

echo "Stopping current PostgreSQL primary: ${initial_primary}"
compose stop "$initial_primary"

new_primary="$(wait_for_cluster 90 "$initial_primary" 1)" || fail "Patroni did not reach degraded one-primary/one-replica topology after stopping ${initial_primary}"
new_primary="$(printf '%s\n' "$new_primary" | tail -n 1)"
[[ "$new_primary" != "$initial_primary" ]] || fail "primary did not change after failure"
wait_for_proxy || fail "database HAProxy did not recover after primary failover"

[[ "$(psql_via_proxy -tAc "SELECT count(*) FROM ha_probe WHERE id='${marker}'" | tr -d '[:space:]')" == "1" ]] \
  || fail "pre-failover marker was lost"

post_marker="after-failover-$(date +%s%N)"
psql_via_proxy -c "INSERT INTO ha_probe(id) VALUES ('${post_marker}')"
wait_for_api || fail "HTTP API did not remain healthy after PostgreSQL failover"

post_realm="ha-failover-$(date +%s%N).turn.yourplatform.com"
post_body="$(mktemp)"
post_code="$(curl -sS -o "$post_body" -w '%{http_code}' -X POST "${BASE_URL}/v1/admin/tenants" \
  -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"HA Failover Probe\",\"realm\":\"${post_realm}\"}" || true)"
if [[ "$post_code" != "201" ]]; then
  cat "$post_body" >&2 || true
  rm -f "$post_body"
  fail "application write after PostgreSQL failover returned HTTP ${post_code:-000}"
fi
rm -f "$post_body"
echo "Application write after PostgreSQL failover: PASS"

echo "Restarting former primary ${initial_primary}"
compose start "$initial_primary"
wait_for_old_primary_replica "$initial_primary" || fail "former primary did not rejoin as a replica"
wait_for_cluster >/dev/null || fail "three-node Patroni topology did not recover after rejoin"
wait_for_marker_on_node "$initial_primary" "$post_marker" || fail "former primary did not catch up after rejoin"

echo "Stopping app1 to prove application redundancy"
compose stop app1
for i in $(seq 1 10); do
  code="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/health" 2>/dev/null || true)"
  [[ "$code" == "200" ]] || fail "API became unavailable with app1 stopped (attempt ${i}, HTTP ${code:-000})"
  sleep 1
done
[[ "$(service_running app2 && echo yes || echo no)" == "yes" ]] || fail "app2 is not running"
[[ "$(service_running app3 && echo yes || echo no)" == "yes" ]] || fail "app3 is not running"
compose start app1
wait_for_apps || fail "application pool did not return to three healthy instances"

echo "===================================================="
echo "PRODUCTION HA VERIFICATION PASSED"
echo "- 3-node etcd quorum bootstrapped"
echo "- Patroni reached 1 primary + 2 replicas"
echo "- replication marker reached both replicas"
echo "- API E2E passed through three-instance load balancer"
echo "- primary ${initial_primary} failed and ${new_primary} was promoted"
echo "- committed data survived failover"
echo "- Spring application executed a write after failover"
echo "- former primary rejoined and caught up as replica"
echo "- API stayed available with app1 stopped"
echo "===================================================="
