#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="${PATRONI_POSTGRESQL_DATA_DIR:-/var/lib/postgresql/data}"

mkdir -p "$DATA_DIR" /var/run/postgresql
chown -R postgres:postgres "$DATA_DIR" /var/run/postgresql
chmod 0700 "$DATA_DIR"

exec gosu postgres patroni /etc/patroni/patroni.yml
