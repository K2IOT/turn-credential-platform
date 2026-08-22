#!/usr/bin/env bash
set -euo pipefail

CONNSTRING="${1:?Patroni post_bootstrap connection string is required}"
APP_DB="${POSTGRES_APP_DB:-turncred}"
APP_USER="${POSTGRES_APP_USER:-turncred}"
APP_PASSWORD="${POSTGRES_APP_PASSWORD:-turncred}"

psql "$CONNSTRING" \
  --set=ON_ERROR_STOP=1 \
  --set=app_db="$APP_DB" \
  --set=app_user="$APP_USER" \
  --set=app_password="$APP_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'app_user', :'app_password')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'app_db', :'app_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'app_db')
\gexec
SQL
