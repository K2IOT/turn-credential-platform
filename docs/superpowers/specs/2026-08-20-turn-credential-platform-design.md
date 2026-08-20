# TURN Credential Platform — Design Spec

- Status: Approved (pending final user sign-off before implementation plan)
- Date: 2026-08-20
- Repo: https://github.com/K2IOT/turn-credential-platform
- Revision history:
  - 2026-08-20 v1: initial multi-region design.
  - 2026-08-20 v2: **scope locked to a single DC.** Multi-region /
    multi-DC (GeoDNS, per-region replicas, cross-DC Patroni standby
    clusters) removed entirely — not a phase-1 goal. Everything below
    describes one data center only.

## 1. Goal

Provide a multi-tenant TURN credential issuance platform. Each customer
("tenant") company gets isolated, revocable credentials for the standard
TURN REST API (`username = expiry:userId`, `password =
base64(HMAC-SHA1(tenantSecret, username))`), backed by a coturn cluster
running inside a single, highly-available data center.

## 2. Non-goals (this phase)

- Multi-DC / multi-region deployment of any kind (GeoDNS, cross-DC
  replication, DR promotion runbooks) — explicitly out of scope.
- Billing/invoicing engine (only usage logging that a billing job can
  consume later).
- Admin UI (tenant management is API + migration-driven for now).

## 3. Scale target

Thousands of tenants, single DC, HA **within that DC**. The DC as a
whole is one failure domain — if the DC goes down, the platform goes
down (accepted trade-off for this phase). Inside the DC, no single
node (Postgres, Redis, coturn, app instance) should be a single point
of failure.

## 4. Architecture — single DC, Patroni-managed Postgres HA cluster

```
                         ┌─────────────────────────────┐
                         │  etcd (3 nodes, local to DC)  │
                         │  Patroni's consensus store     │
                         └───────────────┬───────────────┘
                                          │ leader election / health
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
    ┌──────────────────┐        ┌──────────────────┐        ┌──────────────────┐
    │ Postgres node 1    │        │ Postgres node 2    │        │ Postgres node 3    │
    │ (Patroni agent)    │        │ (Patroni agent)    │        │ (Patroni agent)    │
    │ current: LEADER     │◄──────┤ replica (sync)      │        │ replica (async)     │
    └─────────┬──────────┘        └────────────────────┘        └────────────────────┘
              ▲
              │ writes + reads (routed to current leader)
    ┌─────────┴──────────┐
    │ HAProxy / PgBouncer  │  ← health-checks Patroni REST API (:8008/leader)
    └─────────┬──────────┘
              │
   ┌──────────┼──────────────────────────────┐
   ▼                                          ▼
┌────────────────────┐                ┌────────────────────┐
│ Credential Service   │  N instances    │ Coturn cluster       │
│ (Spring Boot MVC,     │  behind a app-  │  use-auth-secret +   │
│  Java 21 vthreads)    │  level LB       │  psql-userdb          │
└──────────┬───────────┘                └────────────────────┘
           │
           ▼
     ┌──────────┐
     │  Redis     │  rate limiting only
     └──────────┘
```

- **Postgres HA:** 3-node Patroni cluster, all nodes in the same DC
  (low-latency LAN, so Raft leader election via etcd is fast and safe
  — the cross-DC quorum-latency and split-brain risks from the earlier
  multi-DC draft do not apply here). One synchronous replica (zero
  data loss on failover) + one async replica (extra redundancy).
  Automatic failover: ~10-30s.
- **No read/write split needed.** Because everything is in one DC,
  both the Credential Service and coturn connect through
  HAProxy/PgBouncer, which always routes to the *current* leader
  (found via Patroni's REST API health endpoint). There is no replica
  lag to reason about for credential issuance or secret rotation —
  every read sees the latest write immediately.
- **`credential_issuance_log` writes are synchronous**, same as any
  other write — no buffering/async queue needed (this was only a
  concern under the multi-DC draft, where WAN latency made a
  synchronous cross-DC write to a remote primary unacceptable; that
  problem doesn't exist in a single-DC design).
- Redis is **not** used for secrets — same reasoning as before (avoid
  a second source of truth). Used only for the per-tenant rate limiter.

## 5. Components

### 5.1 Credential Service (Spring Boot MVC, Java 21)

- Spring Boot MVC running on the **Java 21 virtual-thread executor**
  (`spring.threads.virtual.enabled=true`).
- Stateless, N instances behind an app-level load balancer within the
  DC — horizontal scaling is just adding instances, no
  region-awareness needed.
- Responsibilities: tenant API-key auth, rate limiting, HMAC-SHA1
  credential signing, issuance logging, tenant CRUD (admin-scoped).
- Connects to Postgres through PgBouncer (transaction pooling) —
  matters at "thousands of tenants" scale because many app instances
  each opening a direct connection pool can exceed Postgres
  `max_connections`.

### 5.2 PostgreSQL — Patroni HA cluster

- 3 nodes, 1 leader + 2 replicas (1 sync, 1 async), managed by Patroni,
  coordinated via a local 3-node etcd cluster.
- HAProxy (or PgBouncer with Patroni-aware health checks) in front,
  always routing to the current leader.
- `turn_secret` and `tenants` tables read by both the Credential
  Service and coturn (`psql-userdb`), both through the same
  HAProxy/PgBouncer endpoint.

### 5.3 Redis

- Single Redis instance (or a small Redis Sentinel setup for its own
  HA, optional for phase 1) — rate limiting only, per-tenant sliding
  window.

### 5.4 Coturn cluster

- `use-auth-secret` + `psql-userdb` pointed at the HAProxy/PgBouncer
  endpoint in front of the Patroni cluster — always reads the current
  leader, so secret rotation is visible immediately.
- Multiple coturn instances behind a UDP/TCP load balancer for relay
  capacity; not tied 1:1 with Postgres node count.

### 5.5 Observability

- Spring Boot Actuator (`/actuator/health`, `/actuator/metrics`,
  `/actuator/prometheus` exposed, not wired to a Prometheus server
  in this phase).
- Structured JSON logs (tenant_id, request_id, latency, outcome) via
  Logback JSON encoder.

## 6. Data model

```sql
CREATE TABLE tenants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,
    realm               VARCHAR(255) NOT NULL UNIQUE,
    api_key_hash        VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    credential_ttl_sec  INT NOT NULL DEFAULT 3600,
    rate_limit_per_min  INT NOT NULL DEFAULT 600,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- read directly by coturn's psql-userdb (read-only role)
CREATE TABLE turn_secret (
    realm       VARCHAR(255) PRIMARY KEY REFERENCES tenants(realm),
    value       VARCHAR(255) NOT NULL,
    previous_value       VARCHAR(255),
    previous_valid_until TIMESTAMPTZ,
    rotated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credential_issuance_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    user_id     VARCHAR(255) NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ttl_sec     INT NOT NULL
);
CREATE INDEX idx_issuance_tenant_time ON credential_issuance_log (tenant_id, issued_at);
```

(`region` column removed — single DC, no longer meaningful.)

## 7. API

```
POST /v1/turn-credentials
  Headers: X-Api-Key: <tenant api key>
  Body (optional): { "userId": "..." }
  200 →
  {
    "username": "1755700000:user-123",
    "password": "base64...",
    "ttlSeconds": 3600,
    "uris": ["turn:turn.yourplatform.com:3478?transport=udp", ...]
  }
  401 → invalid/missing api key
  429 → rate limit exceeded

POST /v1/admin/tenants
POST /v1/admin/tenants/{id}/rotate-secret
```

## 8. Security

- TTL default 1h, tenant-configurable, capped at a platform max.
- API key stored as SHA-256 hash; raw key shown once at creation.
- Secret rotation keeps `previous_value` + `previous_valid_until` for
  a grace period so in-flight sessions signed just before rotation
  still validate.
- TLS everywhere (HTTPS for the API, `turns:` for TURN over TCP/TLS).

## 9. Testing strategy

- Unit tests: HMAC signing correctness, TTL boundary, rate-limiter
  logic (Testcontainers Redis).
- Integration tests: Testcontainers Postgres, asserting tenant
  creation → secret generation → credential issuance end-to-end.
- Contract test: golden HMAC vectors to catch accidental algorithm
  drift.
- Ops-level test (manual, documented in README): kill the Patroni
  leader node, confirm automatic promotion within ~30s and that the
  app/coturn keep working through HAProxy without a config change.

## 10. Deployment / infra (Docker)

- `docker-compose.yml` for local dev: single-node postgres, redis,
  coturn, credential-service (Patroni/etcd/HAProxy are a production
  concern, not needed to develop against locally).
- `docker-compose.prod.yml`: 3-node Patroni Postgres cluster + local
  3-node etcd + HAProxy + Redis + coturn + N credential-service
  instances, all within one DC.

## 11. Open questions (flagged, not blocking implementation start)

1. Redis HA (Sentinel) — deferred to phase 2; a single Redis instance
   losing rate-limit state briefly fails open (requests allowed) or
   closed (requests rejected) depending on the chosen failure mode —
   needs a decision before going to production, not before starting
   implementation.
