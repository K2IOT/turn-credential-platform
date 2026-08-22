# Production HA Compose Design

## Goal

Provide a repeatable production-like deployment topology for TURN Credential Platform with:

- 3-node etcd quorum used by Patroni as the distributed configuration store (DCS).
- 3 PostgreSQL 16 nodes managed by Patroni with streaming replication and automatic failover.
- A write HAProxy endpoint that sends PostgreSQL traffic only to the current Patroni primary.
- 3 Spring Boot credential-service instances behind an API HAProxy endpoint.
- Coturn reading TURN secrets from PostgreSQL through the write HAProxy endpoint.
- CI that proves startup, replication, API availability, and PostgreSQL primary failover.

## Scope and HA Boundary

`docker-compose.prod.yml` provides **process/container-level HA on one Docker host** and is the CI reference topology. It proves that losing one PostgreSQL/Patroni container or one application container does not stop the service.

Docker Compose on one host cannot provide host-level/datacenter-level HA. Production host/node HA requires running the same roles on separate hosts or Kubernetes/another orchestrator. The repository must not claim that a single-host Compose deployment survives loss of the Docker host itself.

## Architecture

```text
                         :8080
Client/API ───────► api-haproxy
                    /    |    \
                  app1  app2  app3
                    \    |    /
                     postgres-write:5000
                             |
                       db-haproxy
                        /   |   \
                      pg1  pg2  pg3
                       |    |    |
                    Patroni Patroni Patroni
                       \    |    /
                      etcd1 etcd2 etcd3

Coturn ───────────────► db-haproxy:5000
```

## PostgreSQL / Patroni

### Image

Build a repository-owned Patroni image from `postgres:16-bookworm` instead of depending on an opaque Spilo image. Install and pin Patroni 4.1.4 with the etcd3 dependency. The image entrypoint runs Patroni as the `postgres` user and leaves PostgreSQL process ownership to Patroni.

### Node identity

A shared `patroni/patroni.yml` contains common cluster settings only. Per-node identity and addresses are supplied through supported environment variables:

- `PATRONI_NAME=pg1|pg2|pg3`
- `PATRONI_POSTGRESQL_CONNECT_ADDRESS=pgN:5432`
- `PATRONI_RESTAPI_CONNECT_ADDRESS=pgN:8008`
- `PATRONI_ETCD3_HOSTS=etcd1:2379,etcd2:2379,etcd3:2379`

### Durability and failover

The cluster initializes with data checksums and `use_pg_rewind: true` so a former primary can rejoin after failover. PostgreSQL enables WAL streaming, replication slots, hot standby, WAL retention, and enough replication senders/slots for the three-node cluster.

Patroni uses synchronous replication with one synchronous standby. This prioritizes committed-transaction durability while retaining a third replica. `maximum_lag_on_failover` is bounded to prevent promotion of a badly lagging node.

### Application database bootstrap

Patroni initializes the PostgreSQL superuser and replication user. A bootstrap post-init script creates/updates the application role and database using environment-supplied credentials. The script is idempotent because it runs only as part of cluster initialization but can safely handle an existing role/database.

## etcd

Run three etcd 3.5.x nodes with dedicated persistent volumes and health checks. Patroni may start only after all three etcd containers are healthy. etcd client and peer ports remain internal to the Compose network.

## HAProxy

### Database HAProxy

Expose PostgreSQL write traffic on port `5000`. Configure all three PostgreSQL servers, but health-check Patroni REST `GET /primary` on port 8008. A backend node is eligible only when Patroni confirms it is the PostgreSQL primary holding the leader lock.

Use `on-marked-down shutdown-sessions` so clients quickly reconnect after primary failure instead of keeping dead connections pinned to the failed node.

### API HAProxy

Run three explicit application services (`app1`, `app2`, `app3`) so standard Docker Compose can start exactly three instances without host-port collisions. None publishes port 8080 to the host. `api-haproxy` publishes host port 8080 and round-robins across the three application containers using `/actuator/health` checks.

## Redis

Redis remains a single service in this change because the requested HA scope is the 3 application replicas and 3-node PostgreSQL/Patroni cluster. This means Redis is still a process-level single point of failure for rate limiting. A separate Redis Sentinel/Redis Cluster change is required before claiming every dependency is fully redundant.

## Coturn

Production Coturn must not use the development `127.0.0.1:5432` PostgreSQL connection. Add a production Coturn config that uses `db-haproxy:5000` for `psql-userdb`.

`TURN_EXTERNAL_IP` is applied through a Compose command-line option so the runtime value is not hard-coded into the config file.

## Secrets and defaults

CI may use deterministic non-secret fallback values so pull requests can bootstrap without repository secrets. Production operators must override:

- `POSTGRES_SUPERUSER_PASSWORD`
- `REPLICATOR_PASSWORD`
- `POSTGRES_APP_USER`
- `POSTGRES_APP_PASSWORD`
- `TURN_EXTERNAL_IP`
- application admin/API secrets as appropriate

No credentials are exposed as host ports or stored in generated artifacts.

## Health and startup ordering

- etcd nodes: `/health` or `etcdctl endpoint health`.
- Patroni nodes: Patroni REST `/health`.
- DB HAProxy: TCP check through port 5000.
- Redis: `redis-cli ping`.
- application instances: `/actuator/health`.
- API HAProxy: HTTP health through port 8080.

`depends_on.condition: service_healthy` is used where Compose supports it so later layers do not race the cluster bootstrap.

## Verification / CI Acceptance Criteria

GitHub Actions must run Maven tests and a production HA integration job. The HA job must:

1. Validate Compose syntax.
2. Build the application JAR and Patroni image.
3. Start `docker-compose.prod.yml` from clean volumes.
4. Assert exactly one Patroni primary and two replicas.
5. Assert HAProxy accepts PostgreSQL connections and the application schema exists.
6. Assert three application containers are running and API HAProxy is healthy.
7. Run the existing API E2E flow through `http://localhost:8080`.
8. Insert a failover marker through HAProxy and verify it is visible on replicas.
9. Stop the current PostgreSQL primary container.
10. Wait for Patroni to promote a different node and for DB HAProxy to recover.
11. Verify the failover marker still exists through HAProxy.
12. Verify the HTTP API remains healthy and can execute a write after failover.
13. Restart the old primary and verify it rejoins as a replica.
14. Stop one application instance and verify API HAProxy still serves requests.
15. Always print Compose/Patroni/HAProxy logs on failure and tear the stack down.

A green CI run is required before the PR can be described as verified.

## Non-goals

- Cross-host Docker scheduling or Kubernetes manifests.
- Redis Sentinel/Cluster.
- Multiple Coturn replicas or TURN anycast/load balancing.
- TLS/mTLS for etcd, Patroni REST, or PostgreSQL internal traffic.
- Backup/PITR tooling.

Those are separate production-hardening workstreams and should not be silently implied by this Compose change.
