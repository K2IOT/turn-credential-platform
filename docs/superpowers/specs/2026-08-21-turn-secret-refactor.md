# Spec: TURN Shared-Secret Persistence Refactoring

- Status: Draft — awaiting user approval before implementation
- Date: 2026-08-21
- Parent spec: `docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md`

---

## Objective

Refactor the `turn_secret` table from a one-row-per-realm model to a
multi-row-per-realm model. Each independent secret (current + previous
grace-period secrets) becomes its own row identified by `(realm, value)`.
This aligns the schema with Coturn's native `turn_secret` schema, removes
application-specific grace-period columns that Coturn cannot understand, and
enables atomic secret rotation without Coturn needing any changes.

**Who benefits:**

- **Coturn** — reads the table the way it was designed to: multiple rows per
  realm, `realm` + `value` only. No application-level logic needed.
- **The application** — clean, predictable queries. "Current secret" is always
  `WHERE valid_until IS NULL`. No ambiguity.
- **Operators** — rotation is atomic; no window where both secrets appear
  "current" (`valid_until IS NULL`).

---

## Tech Stack

- **Language / framework:** Java 21, Spring Boot 3.3.4, Spring Data JPA / Hibernate
- **Database:** PostgreSQL 16, Flyway migrations
- **Test framework:** JUnit 5, Mockito, Testcontainers (PostgreSQL container)
- **Build:** Maven (`mvnw`)

---

## Commands

```bash
./mvnw test
./mvnw test -Dtest=SecretRotationServiceTest
./mvnw test -Dtest=CredentialIssuanceIntegrationTest
docker compose up -d postgres redis
```

---

## Project Structure (files touched by this refactoring)

```
src/
  main/
    java/com/k2iot/turncred/secret/
      TurnSecret.java           <- REWRITE (new composite PK, new fields)
      TurnSecretId.java         <- NEW (composite PK embeddable)
      TurnSecretRepository.java <- REWRITE (new query methods)
      SecretRotationService.java <- REWRITE (transactional rotation)
    resources/db/migration/
      V5__refactor_turn_secret.sql  <- NEW (data-preserving migration)
  test/
    java/com/k2iot/turncred/
      secret/
        SecretRotationServiceTest.java      <- REWRITE (new semantics)
        TurnSecretRepositoryTest.java       <- NEW (repository integration)
      credential/
        TurnCredentialServiceTest.java      <- UPDATE mock stub only
      integration/
        CredentialIssuanceIntegrationTest.java  <- ADD rotation assertions
```

Unchanged: credential_issuance_log, tenants, TenantRepository, Tenant,
TurnCredentialService business logic, TenantAdminController, all auth/rate-limit code.

---

## Target Data Model

### turn_secret table (new)

```sql
CREATE TABLE turn_secret (
    realm       VARCHAR(127) NOT NULL,
    value       VARCHAR(256) NOT NULL,
    valid_until TIMESTAMPTZ,           -- NULL = current secret
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (realm, value),

    CONSTRAINT fk_turn_secret_tenant
        FOREIGN KEY (realm)
        REFERENCES tenants(realm)
        ON DELETE CASCADE
);

-- At most one current secret per realm
CREATE UNIQUE INDEX uq_turn_secret_current
    ON turn_secret(realm)
    WHERE valid_until IS NULL;

-- Optimized cleanup sweep
CREATE INDEX idx_turn_secret_valid_until
    ON turn_secret(valid_until)
    WHERE valid_until IS NOT NULL;
```

### Secret lifecycle

| State           | valid_until       | Readable by Coturn? |
|-----------------|-------------------|---------------------|
| Current         | NULL              | Yes                 |
| Grace-period    | Future timestamp  | Yes                 |
| Expired         | Past timestamp    | Must be deleted     |

### Rotation transaction (logical)

```sql
BEGIN;
UPDATE turn_secret
   SET valid_until = now() + :gracePeriod
 WHERE realm = :realm AND valid_until IS NULL;

INSERT INTO turn_secret (realm, value, valid_until)
VALUES (:realm, :newValue, NULL);
COMMIT;
```

The partial unique index on (realm) WHERE valid_until IS NULL acts as a
database-level guard against double-current bugs.

### Expired-secret cleanup

SecretRotationService.rotate() SHALL delete expired rows for the realm
(same transaction) before inserting the new secret.

---

## JPA Entity Design

### Composite PK: TurnSecretId

```java
@Embeddable
public class TurnSecretId implements Serializable {
    private String realm;   // VARCHAR(127)
    private String value;   // VARCHAR(256)
    // constructor, equals, hashCode, getters, setters
}
```

### TurnSecret entity

```java
@Entity
@Table(name = "turn_secret")
public class TurnSecret {
    @EmbeddedId
    private TurnSecretId id;

    @Column(name = "valid_until")
    private Instant validUntil;   // null = current

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TurnSecret() {}
    public TurnSecret(TurnSecretId id) { this.id = id; }

    public String getRealm()  { return id.getRealm(); }
    public String getValue()  { return id.getValue(); }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
}
```

### TurnSecretRepository

```java
public interface TurnSecretRepository
        extends JpaRepository<TurnSecret, TurnSecretId> {

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm AND s.validUntil IS NULL")
    Optional<TurnSecret> findCurrentByRealm(@Param("realm") String realm);

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm " +
           "AND (s.validUntil IS NULL OR s.validUntil > CURRENT_TIMESTAMP)")
    List<TurnSecret> findValidByRealm(@Param("realm") String realm);

    @Modifying
    @Query("DELETE FROM TurnSecret s WHERE s.id.realm = :realm " +
           "AND s.validUntil IS NOT NULL AND s.validUntil <= CURRENT_TIMESTAMP")
    void deleteExpiredForRealm(@Param("realm") String realm);
}
```

### SecretRotationService.rotate() — new semantics

```java
@Transactional
public void rotate(String realm, Duration graceWindow) {
    secretRepository.deleteExpiredForRealm(realm);

    TurnSecret current = secretRepository.findCurrentByRealm(realm)
        .orElseThrow(() -> new IllegalStateException("No current secret for realm " + realm));
    current.setValidUntil(Instant.now().plus(graceWindow));
    secretRepository.save(current);

    TurnSecret next = new TurnSecret(new TurnSecretId(realm, generateSecret()));
    secretRepository.save(next);
}
```

### TurnCredentialService — minimal change

Replace `secretRepository.findByRealm(realm)` with `secretRepository.findCurrentByRealm(realm)`.

---

## Migration Plan

Flyway V5__refactor_turn_secret.sql (single transaction):

1. Drop `turn_secret_active` view (created by V4; not used by app code).
2. Rename old table to `turn_secret_old`.
3. Create new `turn_secret` with target schema.
4. Copy current secrets:
   INSERT INTO turn_secret (realm, value, valid_until, created_at)
   SELECT realm, value, NULL, rotated_at FROM turn_secret_old;
5. Copy still-valid previous secrets:
   INSERT INTO turn_secret (realm, value, valid_until, created_at)
   SELECT realm, previous_value, previous_valid_until, rotated_at
   FROM turn_secret_old
   WHERE previous_value IS NOT NULL AND previous_valid_until > now();
6. Create uq_turn_secret_current and idx_turn_secret_valid_until.
7. Drop turn_secret_old.

All DDL runs inside one Flyway-managed transaction.

---

## Code Style

- Plain JPA entities with explicit getters/setters (no Lombok in this project).
- Instant for all timestamps.
- @Transactional on any service method that performs multi-step DB writes.
- @Query with JPQL for non-trivial queries.
- Package: com.k2iot.turncred.secret.

Rule: findByRealm(String) is REMOVED. All callers must use findCurrentByRealm().

---

## Testing Strategy

### Unit tests (Mockito, no DB)

SecretRotationServiceTest:
- createInitialSecret saves one row with validUntil = null.
- rotate calls deleteExpiredForRealm, marks expiry, saves new row.
- rotate throws IllegalStateException when no current secret exists.

### Repository integration tests (Testcontainers Postgres)

TurnSecretRepositoryTest:
- findCurrentByRealm returns only the NULL valid_until row.
- findValidByRealm returns current + future grace rows, not expired rows.
- deleteExpiredForRealm deletes only rows with past valid_until.

### Existing unit test update

TurnCredentialServiceTest: swap mock stub from findByRealm to findCurrentByRealm.

### Integration test additions

CredentialIssuanceIntegrationTest:
- After rotation, credential issuance still succeeds.
- Two rows exist for the realm immediately after rotation.
- A second rotation with 1ms grace + sleep confirms expired row is deleted.

---

## Boundaries

Always do:
- ./mvnw test must pass before every commit.
- TurnCredentialService MUST use findCurrentByRealm only.
- deleteExpiredForRealm MUST run before inserting the new secret in rotate().

Ask first:
- Changes to tenants or credential_issuance_log.
- Changing the 15-minute grace-period default in TenantAdminController.
- Adding a new API endpoint for secrets.

Never do:
- Use the removed findByRealm(String) anywhere.
- Leave expired rows indefinitely in turn_secret.
- Store current + previous secret on the same row again.
- Remove the uq_turn_secret_current partial unique index.

---

## Success Criteria

All of the following must be true:

1. ./mvnw test passes with zero failures.
2. One realm can hold multiple turn_secret rows.
3. (realm, value) is the primary key; realm alone is NOT unique.
4. At most one row per realm has valid_until IS NULL (enforced by uq_turn_secret_current).
5. SecretRotationService.rotate() is @Transactional and atomically performs cleanup + expiry + insert.
6. TurnCredentialService signs with the valid_until IS NULL row only.
7. After rotation, SELECT realm, value FROM turn_secret WHERE realm = :realm returns two rows (integration test).
8. Flyway V5 applies cleanly on an existing DB and preserves current + still-valid previous secrets.
9. findByRealm(String) is removed from TurnSecretRepository.
10. previous_value, previous_valid_until, rotated_at columns are gone from turn_secret.

---

## Open Questions

None blocking.

- Scheduled cleanup: Out of scope for this refactoring. rotate() cleans the rotated realm only.
- createInitialSecret: No change needed — no prior rows for a new realm.
- V4 view: turn_secret_active dropped in V5. No app code queries it (confirmed by grep).
