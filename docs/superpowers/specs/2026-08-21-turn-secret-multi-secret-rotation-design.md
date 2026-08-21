# TURN Secret Multi-Secret Rotation — Design Spec

- Status: Approved design amendment
- Date: 2026-08-21
- Repository: `K2IOT/turn-credential-platform`
- Target branch: `feat/turn-credential-platform`
- Parent spec: `docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md`
- Supersedes: the parent spec's one-row-per-realm `turn_secret` model and the `turn_secret_active` compatibility-view approach for secret rotation.

## 1. Goal

Refactor TURN shared-secret persistence so one realm can have multiple secret rows, matching Coturn's native `(realm, value)` cardinality while preserving application-controlled rotation and grace periods.

The final design must ensure that:

- Coturn reads `turn_secret` directly using `realm` and `value`.
- One realm can expose the new secret and one or more grace-period secrets simultaneously.
- New credentials are always signed with exactly one current secret.
- Secret rotation is atomic and safe under concurrent requests.
- Expired previous secrets are physically removed because Coturn does not understand application-specific expiry metadata.
- Existing Flyway history V1-V4 remains untouched.
- Existing tenant and credential REST API shapes remain unchanged.

## 2. Current State on `feat/turn-credential-platform`

The branch currently has:

```sql
CREATE TABLE turn_secret (
    realm                  VARCHAR(255) PRIMARY KEY REFERENCES tenants(realm),
    value                  VARCHAR(255) NOT NULL,
    previous_value         VARCHAR(255),
    previous_valid_until   TIMESTAMPTZ,
    rotated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

It also already has Flyway V4:

```sql
CREATE VIEW turn_secret_active AS
SELECT realm, value
FROM turn_secret
UNION
SELECT realm, previous_value AS value
FROM turn_secret
WHERE previous_value IS NOT NULL
  AND previous_valid_until > NOW();
```

Coturn currently queries that view:

```ini
userdb-user-secret-query="SELECT value FROM turn_secret_active WHERE realm = $1"
```

The compatibility view fixes the immediate grace-period visibility problem, but the requested target model is simpler: each Coturn secret should be a real `turn_secret` row rather than an application-specific current/previous structure hidden behind a view.

## 3. Target Data Model

Each shared secret is an independent row:

```text
realm-a
├── SECRET_V1   valid_until = 2026-08-21T05:00:00Z
├── SECRET_V2   valid_until = 2026-08-21T06:00:00Z
└── SECRET_V3   valid_until = NULL   <- current
```

Target schema:

```sql
CREATE TABLE turn_secret (
    realm       VARCHAR(255) NOT NULL,
    value       VARCHAR(256) NOT NULL,
    valid_until TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (realm, value),

    CONSTRAINT turn_secret_realm_fkey
        FOREIGN KEY (realm)
        REFERENCES tenants(realm)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_turn_secret_current
    ON turn_secret(realm)
    WHERE valid_until IS NULL;

CREATE INDEX idx_turn_secret_expiry
    ON turn_secret(valid_until)
    WHERE valid_until IS NOT NULL;
```

No additional index on `realm` is required because the composite primary-key index already begins with `realm`.

## 4. Secret State Semantics

### Current secret

A current secret is defined by:

```sql
valid_until IS NULL
```

There MUST be at most one current row for a realm. `uq_turn_secret_current` enforces this at the database level.

### Grace-period secret

A previous secret is represented by its own row with:

```sql
valid_until > now()
```

Multiple previous rows MAY coexist for one realm. This allows repeated rotations before earlier grace periods finish.

### Expired secret

An expired row is:

```sql
valid_until <= now()
```

Expired rows MUST be physically deleted. Coturn's direct realm lookup does not apply the application's `valid_until` predicate.

## 5. Application Query Contract

Application code must stop treating `realm` as a unique `turn_secret` identifier.

Credential issuance MUST select only the current row:

```sql
SELECT realm, value, valid_until, created_at
FROM turn_secret
WHERE realm = :realm
  AND valid_until IS NULL;
```

The repository contract becomes conceptually:

```java
Optional<TurnSecret> findCurrentByRealm(String realm);
List<TurnSecret> findAllByRealm(String realm);
int deleteExpired(Instant cutoff);
```

`TurnSecretRepository.findByRealm(String): Optional<TurnSecret>` MUST be removed because its cardinality is no longer valid.

## 6. JPA Identity

`TurnSecret` uses a composite identity matching the database primary key:

```text
TurnSecretId
├── realm
└── value
```

Use `@IdClass(TurnSecretId.class)`.

The entity fields become:

```text
realm       String
value       String
validUntil  Instant?
createdAt   Instant
```

Remove:

```text
previousValue
previousValidUntil
rotatedAt
```

`realm` and `value` are identity fields and MUST NOT be mutated after persistence.

## 7. Concurrency Model

Secret lifecycle operations require a stable per-realm lock.

Do NOT use the current `turn_secret` row itself as the serialization key because rotation changes that row from current to previous. Instead, use the stable parent tenant row:

```sql
SELECT *
FROM tenants
WHERE realm = :realm
FOR UPDATE;
```

Add a repository contract equivalent to:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Tenant> findByRealmForUpdate(String realm);
```

Both `createInitialSecret` and `rotate` MUST acquire this tenant-row lock inside their transaction.

This guarantees that concurrent lifecycle operations for one realm serialize against a row whose identity/state does not change during secret rotation.

## 8. Rotation Transaction

`SecretRotationService.rotate(realm, graceWindow)` performs, in one transaction:

1. Reject null, zero, or negative `graceWindow`.
2. Lock the tenant row by realm using `PESSIMISTIC_WRITE`.
3. Read the current secret using `findCurrentByRealm`.
4. Fail atomically if no current secret exists.
5. Set the old row's `valid_until = now + graceWindow`.
6. Flush the old-row update before inserting the replacement.
7. Generate a new 256-bit random secret.
8. Insert a new row with `valid_until = NULL` and `created_at = now`.
9. Commit.

The explicit flush before INSERT is required because the partial unique index permits only one row with `valid_until IS NULL`. Hibernate is otherwise free to order SQL actions such that the replacement INSERT reaches PostgreSQL before the old-row UPDATE.

Conceptually:

```sql
BEGIN;

SELECT id
FROM tenants
WHERE realm = :realm
FOR UPDATE;

SELECT realm, value
FROM turn_secret
WHERE realm = :realm
  AND valid_until IS NULL;

UPDATE turn_secret
SET valid_until = :previousValidUntil
WHERE realm = :realm
  AND value = :oldValue;

-- flush UPDATE before INSERT

INSERT INTO turn_secret (realm, value, valid_until, created_at)
VALUES (:realm, :newSecret, NULL, :now);

COMMIT;
```

## 9. Initial Secret Creation

`createInitialSecret(realm)` also locks the tenant row first.

After acquiring the lock:

- fail if a current secret already exists;
- otherwise insert exactly one row with `valid_until = NULL`.

The partial unique index remains a final invariant guard.

## 10. Expired Secret Cleanup

A scheduled cleanup job is required after Coturn moves from the filtered V4 view to direct table reads.

The job runs with a checked-in interval of at most 10 seconds and executes:

```sql
DELETE FROM turn_secret
WHERE valid_until IS NOT NULL
  AND valid_until <= :now;
```

The cleanup is idempotent and safe on multiple stateless application instances. Concurrent bulk deletes may race, but deleting the same expired rows is harmless.

Coturn may accept a just-expired previous secret for at most one cleanup interval. Exact zero-lag expiration would require a filtered lookup surface and is intentionally not the chosen model.

## 11. Flyway Migration Strategy

V1-V4 already exist on the target branch and MUST NOT be rewritten.

Add:

```text
V5__refactor_turn_secret_multi_secret.sql
```

V5 must account for the existing `turn_secret_active` view before changing columns it depends on.

### Migration precondition

If any legacy row has:

```text
previous_value IS NOT NULL
previous_valid_until IS NULL
```

V5 MUST abort. Turning such a secret into a permanent Coturn row would weaken authentication semantics.

### Migration mapping

For each legacy row:

- `value` remains the current row with `valid_until = NULL`;
- current-row `created_at` is initialized from `rotated_at`;
- an unexpired `previous_value` becomes a second row with `valid_until = previous_valid_until`;
- an already-expired previous secret is not migrated;
- `previous_value = value` is skipped as a duplicate.

### Migration sequence

In one PostgreSQL transaction:

1. Validate ambiguous legacy rows.
2. `DROP VIEW turn_secret_active`.
3. Drop the old `turn_secret` primary key and tenant foreign key.
4. Add `valid_until` and `created_at`.
5. Backfill `created_at` for existing current rows.
6. Insert still-valid legacy previous values as independent rows.
7. Drop `previous_value`, `previous_valid_until`, and `rotated_at`.
8. Widen `value` to `VARCHAR(256)`.
9. Add `PRIMARY KEY (realm, value)`.
10. Recreate `turn_secret.realm -> tenants.realm ON DELETE CASCADE`.
11. Create `uq_turn_secret_current`.
12. Create `idx_turn_secret_expiry`.

PostgreSQL transactional DDL ensures a failure rolls the schema/data back to the V4 state.

## 12. Coturn Configuration

After V5, Coturn no longer needs `turn_secret_active`.

Change:

```ini
userdb-user-secret-query="SELECT value FROM turn_secret_active WHERE realm = $1"
```

to:

```ini
userdb-user-secret-query="SELECT value FROM turn_secret WHERE realm = $1"
```

The PostgreSQL role remains read-only.

## 13. Deployment Compatibility

V5 removes columns and a view used by the old application/Coturn configuration. Therefore this is a coordinated migration, not a mixed-version rolling migration.

Deployment MUST ensure that old application instances and old Coturn configuration do not continue serving after V5 commits.

A zero-downtime expand/contract rollout is out of scope for this refactor.

## 14. API Compatibility

External API shapes remain unchanged:

```text
POST /v1/turn-credentials
POST /v1/admin/tenants
POST /v1/admin/tenants/{id}/rotate-secret
```

Only persistence and rotation internals change.

## 15. Failure Handling

- Credential issuance with no current secret: preserve existing failure behavior.
- Rotation with no current secret: fail transaction; do not implicitly recreate one.
- Unknown realm: fail before secret mutation.
- Non-positive grace period: reject before persistence changes.
- Concurrent same-realm lifecycle requests: serialize on the tenant-row lock.
- Random 256-bit secret collision: allow the PK constraint to fail; no retry complexity is needed in this phase.
- Cleanup failure: log and allow the next scheduled run to retry.

## 16. Testing Requirements

### Migration tests

Use Testcontainers PostgreSQL + Flyway to verify:

- V1-V4 -> V5 preserves the current secret;
- an unexpired previous secret becomes another row;
- an already-expired previous secret is not migrated;
- ambiguous previous secret without expiry aborts V5 and rolls back;
- the V4 view is removed;
- tenant deletion cascades to `turn_secret` after V5.

### Repository tests

Verify:

- multiple rows can share a realm when values differ;
- exactly one current row is allowed;
- composite identities load each row independently;
- `findCurrentByRealm` selects only the current row.

### Rotation tests

Verify:

- initial creation produces one current row;
- rotation expires the old row and inserts a replacement;
- repeated rotations may retain multiple grace rows;
- invalid grace windows are rejected;
- missing current secret fails atomically;
- two concurrent rotations on one realm serialize and finish with exactly one current row.

### Credential issuance test

With both current and previous rows present, assert the service signs only with the current secret.

### Coturn database-contract test

Execute the raw query:

```sql
SELECT value
FROM turn_secret
WHERE realm = ?;
```

Assert that current + grace rows are visible during grace, and only the current row is visible after expiry cleanup.

## 17. Acceptance Criteria

The refactor is complete when:

- target branch is `feat/turn-credential-platform`;
- V1-V4 remain unchanged;
- V5 performs the multi-row migration;
- `turn_secret_active` is removed;
- Coturn queries `turn_secret` directly;
- `turn_secret` uses PK `(realm, value)`;
- one realm can store multiple secrets;
- at most one current row exists per realm;
- credential issuance uses only the current row;
- secret lifecycle operations serialize on the tenant row;
- rotation flushes the old-row expiry before replacement insertion;
- expired grace rows are physically removed within the configured <=10s cleanup interval;
- current and valid previous secrets survive migration correctly;
- existing REST API shapes remain unchanged;
- migration, repository, rotation, concurrency, credential, cleanup, and raw Coturn contract tests pass.

## 18. Non-Goals

This refactor does not add:

- another compatibility view;
- a separate secret-management table;
- secret-history APIs;
- arbitrary secret version IDs;
- long-term audit storage of expired secret values;
- multi-DC changes;
- mixed-version zero-downtime schema rollout.
