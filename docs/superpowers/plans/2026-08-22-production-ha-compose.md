# Production HA Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a CI-verified 3-node Patroni/PostgreSQL HA cluster and three Spring Boot service instances behind load balancing in the production Compose topology.

**Architecture:** Build a repository-owned PostgreSQL 16 + Patroni image, use a three-node etcd DCS, route writes through HAProxy based on Patroni `/primary`, run three explicit application instances behind a second HAProxy, and validate real primary failover in GitHub Actions.

**Tech Stack:** Docker Compose, PostgreSQL 16, Patroni 4.1.4, etcd 3.5.x, HAProxy 2.9, Spring Boot 3.3.4 / Java 21, Coturn, Bash, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-22-production-ha-compose-design.md`

## Global Constraints

- Production Compose must run 3 etcd members, 3 Patroni/PostgreSQL members, and 3 Spring Boot application instances.
- PostgreSQL client writes must enter through HAProxy port 5000 and only reach the Patroni-confirmed primary.
- The public application endpoint remains host port 8080 and survives one application instance failure.
- CI must prove PostgreSQL failover, data survival, old-primary rejoin, and API availability.
- Single-host Compose is process/container HA only; do not claim host-level HA.
- Redis remains standalone in this change and must be documented as a remaining SPOF.

---

### Task 1: Repository-owned Patroni image and deterministic cluster configuration

**Files:**
- Create: `patroni/Dockerfile`
- Create: `patroni/entrypoint.sh`
- Create: `patroni/post-bootstrap.sh`
- Modify: `patroni/patroni.yml`

**Interfaces:**
- Consumes: `PATRONI_NAME`, `PATRONI_POSTGRESQL_CONNECT_ADDRESS`, `PATRONI_RESTAPI_CONNECT_ADDRESS`, `PATRONI_ETCD3_HOSTS`, PostgreSQL credential environment variables.
- Produces: a `turncred-patroni:16` image exposing PostgreSQL 5432 and Patroni REST 8008.

- [ ] **Step 1: Add structural failing checks**

Create assertions in the HA verification script introduced in Task 4 for the image/config contract: Patroni version is pinned, no `${NODE_NAME}`/`${NODE_IP}` placeholders remain in YAML, and the image contains `patroni` + `patronictl`.

- [ ] **Step 2: Verify the checks fail against the current branch**

Run:
```bash
./scripts/verify-ha.sh --static-only
```
Expected: FAIL because the Patroni image and HA verification script/config contract do not yet exist.

- [ ] **Step 3: Implement the Patroni image**

Use `postgres:16-bookworm`; install Python/pip and `patroni[etcd3]==4.1.4`; copy the shared config and bootstrap scripts; run Patroni as the `postgres` user. The entrypoint must ensure `/var/lib/postgresql/data` ownership before `exec patroni /etc/patroni/patroni.yml`.

- [ ] **Step 4: Replace unsupported YAML interpolation with Patroni env overrides**

Keep only common values in `patroni.yml`: scope/namespace, REST listen, etcd3 DCS, bootstrap DCS settings, `initdb`, `pg_hba`, PostgreSQL listen/data directory/parameters, authentication usernames, and `post_bootstrap` command. Per-node name/connect addresses must come from supported `PATRONI_*` environment variables.

- [ ] **Step 5: Add idempotent application role/database bootstrap**

`patroni/post-bootstrap.sh` must create `POSTGRES_APP_USER` when absent, update its password when present, and create `POSTGRES_APP_DB` owned by that user when absent. Use `psql -v ON_ERROR_STOP=1` against the local primary.

- [ ] **Step 6: Run static/build verification**

Run:
```bash
docker build -t turncred-patroni:test patroni
./scripts/verify-ha.sh --static-only
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add patroni scripts/verify-ha.sh
git commit -m "feat: build deterministic Patroni PostgreSQL image"
```

### Task 2: Replace fake production database topology with real Patroni HA

**Files:**
- Modify: `docker-compose.prod.yml`

**Interfaces:**
- Consumes: Patroni image from Task 1.
- Produces: healthy etcd1/2/3 and pg1/2/3 services with persistent volumes and automatic replication/failover.

- [ ] **Step 1: Extend failing topology assertions**

The static verification must require exactly three etcd services, three pg services built from `patroni/Dockerfile`, no `postgres:16` standalone pg services, persistent etcd/pg volumes, and Patroni REST/DB health checks.

- [ ] **Step 2: Run static verification and observe RED**

Run:
```bash
./scripts/verify-ha.sh --static-only
```
Expected: FAIL against the old Compose topology.

- [ ] **Step 3: Implement etcd quorum**

Configure etcd1/2/3 with explicit names, peer/client URLs, one shared initial-cluster declaration, `ETCD_INITIAL_CLUSTER_TOKEN`, dedicated data directories/volumes, and health checks. Keep 2379/2380 internal.

- [ ] **Step 4: Implement pg1/pg2/pg3 Patroni services**

Use one YAML anchor for common build/environment/volume settings, override `PATRONI_NAME` and connect addresses per node, depend on healthy etcd, expose 5432/8008 only to the internal network, and attach independent persistent volumes.

- [ ] **Step 5: Configure durability settings**

Set synchronous mode with one synchronous standby, data checksums, `use_pg_rewind`, replication slots, WAL sender/slot capacity, hot standby, WAL retention, and bounded failover lag.

- [ ] **Step 6: Validate rendered Compose**

Run:
```bash
docker compose -f docker-compose.prod.yml config
./scripts/verify-ha.sh --static-only
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "feat: run PostgreSQL as three-node Patroni cluster"
```

### Task 3: HAProxy, three app instances, and Coturn database routing

**Files:**
- Modify: `haproxy/haproxy.cfg`
- Create: `haproxy/api-haproxy.cfg`
- Create: `coturn/turnserver.prod.conf`
- Modify: `docker-compose.prod.yml`

**Interfaces:**
- Consumes: pg1/pg2/pg3 Patroni REST health and three Spring Boot containers.
- Produces: database write endpoint `db-haproxy:5000` and public API endpoint `localhost:8080`.

- [ ] **Step 1: Add RED checks for routing contracts**

Static checks must require all three pg nodes in DB HAProxy, Patroni `/primary` health checks on port 8008, three app services with no host 8080 binding, one API HAProxy with host 8080, and production Coturn using `db-haproxy:5000`.

- [ ] **Step 2: Run static verification and observe RED**

Run:
```bash
./scripts/verify-ha.sh --static-only
```
Expected: FAIL.

- [ ] **Step 3: Configure database HAProxy**

Use TCP mode on port 5000, HTTP health checks against Patroni REST `/primary`, all three pg backends, fast fail/rise intervals, and `on-marked-down shutdown-sessions`.

- [ ] **Step 4: Configure API HAProxy**

Use HTTP mode, bind 8080, health-check `/actuator/health`, and round-robin `app1:8080`, `app2:8080`, `app3:8080`.

- [ ] **Step 5: Replace one app replica declaration with explicit app1/app2/app3**

All instances use the same image/build and database/Redis environment, depend on healthy DB HAProxy and Redis, and do not publish host ports. Add `api-haproxy` as the sole host `8080:8080` publisher.

- [ ] **Step 6: Add production Coturn config**

Use `psql-userdb` host `db-haproxy`, port 5000, the same secret query as development, and apply `TURN_EXTERNAL_IP` through Compose command interpolation.

- [ ] **Step 7: Validate config**

Run:
```bash
docker compose -f docker-compose.prod.yml config
./scripts/verify-ha.sh --static-only
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add docker-compose.prod.yml haproxy coturn
git commit -m "feat: load balance database and application HA endpoints"
```

### Task 4: Real HA/failover acceptance test

**Files:**
- Create: `scripts/verify-ha.sh`
- Modify: `scripts/verify-e2e.sh`

**Interfaces:**
- Consumes: production Compose topology.
- Produces: one command that returns non-zero unless bootstrap, replication, failover and API redundancy all work.

- [ ] **Step 1: Implement static mode first**

`./scripts/verify-ha.sh --static-only` must validate required files and topology contracts without starting Docker.

- [ ] **Step 2: Implement clean startup and diagnostics trap**

Before starting, run `docker compose -f docker-compose.prod.yml down -v --remove-orphans`. Add an EXIT trap that dumps `docker compose ps`, Patroni logs, HAProxy logs, and application logs on failure, then tears down the stack.

- [ ] **Step 3: Assert cluster topology**

Poll Patroni REST endpoints until exactly one pg node returns HTTP 200 for `/primary` and two return HTTP 200 for `/replica`. Fail if this invariant is not reached.

- [ ] **Step 4: Assert replication and application topology**

Insert a unique marker through `db-haproxy:5000`; verify it is queryable on both replicas. Assert app1/app2/app3 are running and `http://localhost:8080/actuator/health` returns 200.

- [ ] **Step 5: Reuse API E2E without re-owning stack lifecycle**

Add `SKIP_BUILD` and `SKIP_COMPOSE_UP` switches to `scripts/verify-e2e.sh`, keeping existing behavior as default. HA verification calls it with the production stack already running.

- [ ] **Step 6: Test primary failure**

Detect the current primary service name, stop that container, wait for another node to become `/primary`, wait for DB HAProxy to accept SQL again, and assert the new primary differs from the old one.

- [ ] **Step 7: Verify data/API continuity**

Query the pre-failover marker through HAProxy and execute a new write. Verify `/actuator/health` still returns 200.

- [ ] **Step 8: Test old-primary rejoin**

Restart the stopped service and wait for `/replica` to return 200. Verify three Patroni members are healthy again.

- [ ] **Step 9: Test one app failure**

Stop `app1`; verify repeated API health calls through API HAProxy remain 200 and at least app2/app3 remain healthy.

- [ ] **Step 10: Run full HA acceptance locally/CI runner**

Run:
```bash
./scripts/verify-ha.sh
```
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add scripts
git commit -m "test: prove Patroni failover and application redundancy"
```

### Task 5: GitHub Actions production HA gate

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Maven tests and `scripts/verify-ha.sh`.
- Produces: PR/push checks that block unverified HA topology changes.

- [ ] **Step 1: Add a unit/build job**

Use Ubuntu, Java 21 Temurin, Maven cache, and run:
```bash
./mvnw -B test
./mvnw -B package -DskipTests
```

- [ ] **Step 2: Add HA integration job**

Depend on the build job, package the JAR, run `docker compose ... config`, then `./scripts/verify-ha.sh`. Set a job timeout so a broken election cannot hang indefinitely.

- [ ] **Step 3: Upload diagnostics on failure**

Always collect Compose logs into a text artifact when the HA job fails.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: verify production Patroni HA failover"
```

### Task 6: Documentation, CI debugging, and final verification

**Files:**
- Modify: `README.md`
- Modify as required by observed failures: files from Tasks 1-5 only.

**Interfaces:**
- Consumes: green CI evidence.
- Produces: deploy/runbook documentation and a verified PR.

- [ ] **Step 1: Update production topology documentation**

Document the exact start/verify commands, environment variables, failure semantics, and the single-host Compose HA limitation. Remove claims that Redis or Coturn are redundant when they are not.

- [ ] **Step 2: Push/open PR and inspect workflow run**

Open a draft PR from `feat/production-ha-patroni` to `main`. Fetch workflow jobs/logs for failures.

- [ ] **Step 3: Debug every CI failure systematically**

For each failure: identify the failing layer, reproduce via the smallest script/command, change only the root cause, and rerun the failed workflow/job. Do not weaken an HA assertion to make CI green.

- [ ] **Step 4: Verify final branch diff**

Compare branch to `main`; confirm changes are limited to the HA spec/plan, Patroni, Compose, HAProxy, Coturn production config, scripts, CI, and README.

- [ ] **Step 5: Require final evidence**

A completion claim requires all Maven tests green and the HA workflow green, including primary failover, marker survival, old-primary rejoin, and API survival with one app instance stopped.

- [ ] **Step 6: Final commit if documentation/debug changes remain**

```bash
git add README.md docker-compose.prod.yml patroni haproxy coturn scripts .github/workflows/ci.yml
git commit -m "docs: document verified production HA deployment"
```
