# TURN Credential Platform

Multi-tenant service for issuing ephemeral TURN REST API credentials, managing tenant/user secrets, and running a production-like HA reference topology.

## Architecture

### Local development

The default `docker-compose.yml` is intended for local development and E2E testing.

### Production HA reference

`docker-compose.prod.yml` is the CI reference topology:

```text
                         :8080
Client/API ───────► api-haproxy
                    /    |    \
                  app1  app2  app3
                    \    |    /
                     db-haproxy:5000
                            |
                     PostgreSQL writes
                    /       |       \
                  pg1      pg2      pg3
                   |        |        |
                Patroni  Patroni  Patroni
                    \       |       /
                    etcd1  etcd2  etcd3

Coturn ─────────────► db-haproxy:5000
Redis ──────────────► app1/app2/app3
```

The database HAProxy uses Patroni `GET /primary` on port 8008 and only routes PostgreSQL traffic to the current primary. API HAProxy round-robins across `app1`, `app2`, and `app3` and health-checks `/actuator/health`.

> **HA boundary:** this Compose topology provides process/container-level HA on a **single Docker host**. It does not survive loss of that host. Cross-host/node HA requires Kubernetes or another orchestrator with the roles distributed across independent hosts.

Remaining single points of failure in this reference topology:

- Redis is standalone.
- Coturn is a single instance.
- API HAProxy and DB HAProxy are single instances inside the single-host Compose reference.
- Internal etcd/Patroni/PostgreSQL traffic is not TLS/mTLS protected by this change.
- Backup/PITR is not implemented by this change.

## API Usage Flow

1. An administrator creates a tenant with `POST /v1/admin/tenants`.
2. The tenant pre-registers/activates users through the admin endpoints.
3. A tenant application requests credentials with `POST /v1/turn-credentials`.
4. The service returns a timestamped TURN username and HMAC password.
5. The client uses those credentials against Coturn.

Example tenant onboarding:

```bash
curl -i -X POST http://localhost:8080/v1/admin/tenants \
  -H "X-Admin-Api-Key: dev-admin-key" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Acme Corp",
        "realm": "acme.turn.yourplatform.com"
      }'
```

Example credential issuance:

```bash
curl -i -X POST http://localhost:8080/v1/turn-credentials \
  -H "X-Api-Key: <tenant-api-key>" \
  -H "Content-Type: application/json" \
  -d '{ "userId": "user-42" }'
```

The response contains `username`, `password`, `ttlSeconds`, and TURN URIs.

## Main API Endpoints

- `POST /v1/admin/tenants` — create a tenant.
- `POST /v1/admin/tenants/{tenantId}/users` — register a user.
- `POST /v1/admin/tenants/{tenantId}/users/{userId}/rotate-secret` — rotate a user secret.
- `DELETE /v1/admin/tenants/{tenantId}/users/{userId}` — suspend/deregister a user.
- `POST /v1/turn-credentials` — issue ephemeral TURN credentials.
- `GET /actuator/health` — health check.
- `GET /actuator/prometheus` — Prometheus metrics.

## Local Development

Build and start the default stack:

```bash
./mvnw clean package -DskipTests
docker compose up -d --build
```

Run unit tests:

```bash
./mvnw test
```

## End-to-End Verification

The existing API E2E suite is:

```bash
./scripts/verify-e2e.sh
```

It validates the 9-step API workflow for tenant onboarding, registration, credential issuance, secret rotation, deregistration, and expected error handling.

When called by the HA verifier, the E2E suite reuses the already-running production topology instead of rebuilding/restarting it.

## Production HA Deployment

The production-like reference stack is started with:

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

Before a real deployment, override at least:

```text
POSTGRES_SUPERUSER_PASSWORD
REPLICATOR_PASSWORD
POSTGRES_APP_DB
POSTGRES_APP_USER
POSTGRES_APP_PASSWORD
TURN_ADMIN_API_KEY
TURN_EXTERNAL_IP
TURN_PLATFORM_DOMAIN
```

The deterministic fallback values in `docker-compose.prod.yml` exist so CI can bootstrap without repository secrets. They are not production credentials.

### Full HA acceptance

Run the same acceptance gate used by CI:

```bash
./scripts/verify-ha.sh
```

The verifier starts from clean volumes and requires all of the following:

1. Three healthy etcd members.
2. One Patroni primary and two replicas.
3. SQL connectivity through DB HAProxy.
4. Replication of a committed marker to both replicas.
5. Three healthy Spring Boot application instances.
6. The complete 9-case API E2E flow through API HAProxy.
7. Failure of the current PostgreSQL primary and promotion of a different node.
8. Survival of committed data and a successful application write after failover.
9. Rejoin/catch-up of the former primary as a replica.
10. Continued API availability while `app1` is stopped.
11. Recovery to three healthy application instances after `app1` restarts.

Static topology/config validation can be run separately:

```bash
./scripts/verify-ha.sh --static-only
docker compose -f docker-compose.prod.yml config -q
```

## Coturn Production Notes

The production Coturn container is pinned to a specific upstream image version and connects to PostgreSQL through `db-haproxy:5000`.

Upstream Coturn's PostgreSQL driver loads TURN REST API shared secrets with a realm lookup equivalent to:

```sql
SELECT value FROM turn_secret WHERE realm = $1;
```

Coturn does **not** expose a `userdb-user-secret-query` configuration option. Therefore this HA change does not claim that Coturn itself enforces this project's `user_id` or `valid_until` columns. The per-user/secret-expiry compatibility gap is a separate correctness/hardening workstream and must not be considered solved merely because the HA topology is green.

### TURNS / port 5349

`turnserver.prod.conf` declares the standard TLS listener port, but production certificates/private keys are not provisioned by this Compose reference. A real TURNS deployment must mount/configure `cert` and `pkey` before relying on the `turns:...:5349` URI returned by the application.

Plain TURN on port 3478 is independent of that certificate requirement.

## Secret Rotation Model

`turn_secret` supports multiple secrets per realm so the application can preserve a grace period during rotation. The application chooses the current secret for new credential issuance and records expiry metadata for older rows.

Because upstream Coturn's PostgreSQL shared-secret query is realm-only, enforcement of per-user binding and database-side `valid_until` filtering requires additional integration work beyond this HA PR. Credential usernames still contain their own expiration timestamp, which Coturn validates as part of TURN REST API authentication.

## CI

`.github/workflows/ci.yml` runs:

- `test` — Maven unit tests.
- `ha-static-contract` — HA topology/config contract plus rendered Compose validation.
- `ha-integration` — clean production topology, API E2E, PostgreSQL failover/rejoin, data survival, and application redundancy.

E2E currently runs inside `ha-integration`; it is not a separate GitHub Actions job.

## Production Hardening Still Out of Scope

The following are intentionally separate workstreams:

- Redis Sentinel/Cluster.
- Multiple Coturn instances / TURN load balancing or anycast.
- Coturn per-user and `valid_until` database filtering compatibility.
- TURNS certificate lifecycle and secret mounts.
- Internal TLS/mTLS for etcd, Patroni REST, and PostgreSQL.
- PostgreSQL backup/PITR.
- Cross-host scheduling and host/datacenter failure handling.
