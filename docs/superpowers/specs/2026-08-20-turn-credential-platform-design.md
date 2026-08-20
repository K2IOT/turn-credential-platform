# TURN Credential Platform — Design Spec

- Status: Approved (pending final user sign-off before implementation plan)
- Date: 2026-08-20
- Repo: https://github.com/K2IOT/turn-credential-platform

## 1. Goal

Provide a multi-tenant TURN credential issuance platform. Each customer
("tenant") company gets isolated, revocable credentials for the standard
TURN REST API (`username = expiry:userId`, `password =
base64(HMAC-SHA1(tenantSecret, username))`), backed by a coturn cluster
running in multiple regions.

## 2. Non-goals (this phase)

- Billing/invoicing engine (only usage logging that a billing job can
  consume later).
- Admin UI (tenant management is API + migration-driven for now).
- Multi-cloud DNS failover automation (documented as an ops runbook,
  not built in code).

## 3. Scale target

500+ tenants, multi-region, HA. No single region's outage should stop
credential issuance for tenants whose traffic is served by other
regions; only tenant/secret *writes* depend on the primary region.

## 4. Architecture (Approach A: single global Postgres primary +
per-region read replicas, Redis used only for rate-limiting)

```
                         ┌────────────────────────┐
                         │   Postgres PRIMARY      │
                         │  (tenants, turn_secret,  │
                         │  issuance_log — writes)  │
                         └───────────┬─────────────┘
                     streaming replication (per region)
              ┌───────────────┼───────────────┐
              ▼                               ▼
   ┌─────────────────────┐         ┌─────────────────────┐
   │ Region A             │         │ Region B             │
   │ Postgres REPLICA     │         │ Postgres REPLICA     │
   │ Redis (rate-limit)   │         │ Redis (rate-limit)   │
   │ Credential Service    │         │ Credential Service    │
   │  (Spring Boot MVC,    │         │  (Spring Boot MVC,    │
   │   Java 21 vthreads)   │         │   Java 21 vthreads)   │
   │ Coturn cluster        │         │ Coturn cluster        │
   │  (psql-userdb→replica)│         │  (psql-userdb→replica)│
   └─────────────────────┘         └─────────────────────┘
              ▲                               ▲
        GeoDNS / Anycast routes tenant clients to nearest region
```

- **Writes** (create tenant, rotate secret) always go to the primary
  region's Postgres, through the Credential Service in that region (or
  proxied — see §8 open question).
- **Reads** (credential issuance, coturn secret lookup) happen against
  the local region's replica. Replication lag is bounded by
  `credential_ttl_sec` (minutes), which is an acceptable staleness
  window: a client using a stale-but-still-valid secret keeps working;
  a client using a *just-rotated* secret may see a few seconds of
  replica lag before the new secret is visible in that region.
- Redis is **not** used for secrets (avoids a second source-of-truth /
  cache-invalidation bug class). It is used exclusively for the
  per-tenant token-bucket rate limiter, one Redis instance per region
  (no cross-region rate-limit coordination — acceptable since limits
  are generous per-tenant ceilings, not hard global caps).

## 5. Components

### 5.1 Credential Service (Spring Boot MVC, Java 21)

- Spring Boot MVC (not WebFlux, per requirement) running on the
  **Java 21 virtual-thread executor** (`spring.threads.virtual.enabled=true`),
  so blocking JDBC/Redis calls scale without a reactive rewrite.
- Stateless, horizontally scalable; one deployment per region.
- Responsibilities: tenant API-key auth, rate limiting, HMAC-SHA1
  credential signing, issuance logging, tenant CRUD (admin-scoped).

### 5.2 PostgreSQL

- Primary (read-write) in one region; streaming replicas in every
  other region. `turn_secret` and `tenants` are read by both the
  Credential Service (local replica) and coturn (`psql-userdb`,
  read-only role, local replica).

### 5.3 Redis (per region)

- Rate limiting only (sliding-window counters keyed by `tenant_id`).
- No cross-region replication needed — each region enforces its own
  ceiling independently.

### 5.4 Coturn cluster (per region)

- `use-auth-secret` + `psql-userdb` pointed at the local read replica,
  looking up `static-auth-secret` by `realm`.
- Fronted by a regional load balancer / floating IP for UDP relay
  traffic (coturn itself is not horizontally load-balanced behind a
  single VIP for relay traffic in the classic sense — documented as an
  ops concern in the runbook, out of scope for the app-level spec).

### 5.5 Observability

- Spring Boot Actuator (`/actuator/health`, `/actuator/metrics`,
  `/actuator/prometheus` exposed but not wired to a Prometheus
  server in this phase — kept available for the next phase).
- Structured JSON logs (tenant_id, request_id, latency, outcome) via
  Logback JSON encoder, shippable to any log aggregator later.

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
    rotated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credential_issuance_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    user_id     VARCHAR(255) NOT NULL,
    region      VARCHAR(50)  NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ttl_sec     INT NOT NULL
);
CREATE INDEX idx_issuance_tenant_time ON credential_issuance_log (tenant_id, issued_at);
```

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
    "uris": ["turn:turn.<region>.yourplatform.com:3478?transport=udp", ...]
  }
  401 → invalid/missing api key
  429 → rate limit exceeded

POST /v1/admin/tenants        (internal/admin auth, primary region only)
POST /v1/admin/tenants/{id}/rotate-secret
```

## 8. Security

- TTL default 1h, tenant-configurable, capped at a platform max.
- API key stored as SHA-256 hash; raw key shown once at creation.
- Secret rotation supports a grace period: `turn_secret` keeps
  `previous_value` + `previous_valid_until` so in-flight sessions
  signed just before rotation still validate (coturn checks current
  value only — grace handled by keeping old value valid at the app
  level for the overlap window, then a scheduled job clears it).
- All admin/tenant-write endpoints only served from the primary
  region's Credential Service instance (guarded by a `write-enabled`
  profile flag), so writes never hit a replica.
- TLS everywhere (HTTPS for the API, `turns:` for TURN over TCP/TLS).

## 9. Testing strategy

- Unit tests: HMAC signing correctness, TTL boundary, rate-limiter
  logic (Testcontainers Redis).
- Integration tests: Testcontainers Postgres + Testcontainers coturn
  container, asserting an issued credential actually authenticates
  against a real coturn instance.
- Contract test: golden HMAC vectors to catch accidental algorithm
  drift.

## 10. Deployment / infra (Docker)

- `docker-compose.yml` for local dev: postgres (single node), redis,
  coturn, credential-service.
- `docker-compose.prod.yml` (per region) wiring the region's
  Credential Service + Redis + coturn to the correct Postgres
  endpoint (primary or replica) via environment variables — used as
  the reference topology; actual multi-region rollout is
  orchestrated per the ops runbook (out of scope for app code).

## 11. Open questions (flagged, not blocking implementation start)

1. Admin writes: route through the primary region's service directly,
   or add a lightweight write-proxy in every region? → **Decision:**
   direct routing for phase 1 (simpler); revisit if tenant-management
   traffic volume grows.
2. Cross-region rate-limit ceilings are independent, not global. If a
   tenant is later found abusing multiple regions simultaneously,
   this is a phase-2 concern (global Redis or a shared counter).
