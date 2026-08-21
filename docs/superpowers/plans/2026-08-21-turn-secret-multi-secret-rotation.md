# TURN Secret Multi-Secret Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `turn_secret` on `feat/turn-credential-platform` from one mutable row per realm plus V4 compatibility view into Coturn-native multiple secret rows per realm, migrate existing data with Flyway V5, issue credentials only from the current secret, serialize lifecycle changes on the stable tenant row, and physically delete expired grace-period rows.

**Architecture:** Keep a single PostgreSQL `turn_secret` table shared by the Credential Service and Coturn. Use `(realm, value)` as the composite key; `valid_until IS NULL` identifies the current row and future `valid_until` identifies grace rows. V5 removes the existing `turn_secret_active` view, converts legacy previous secrets into rows, and Coturn switches to a direct table query. Secret creation/rotation lock `tenants.realm`; rotation flushes the old-row expiry before inserting the replacement.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Data JPA, PostgreSQL 16, Flyway, Maven Wrapper, JUnit 5, AssertJ, Testcontainers PostgreSQL, Coturn.

**Spec:** `docs/superpowers/specs/2026-08-21-turn-secret-multi-secret-rotation-design.md`

## Global Constraints

- Target branch: `feat/turn-credential-platform`.
- Leave Flyway V1-V4 unchanged.
- Add `V5__refactor_turn_secret_multi_secret.sql`.
- Preserve existing REST endpoint shapes.
- Remove the V4 `turn_secret_active` view as part of V5.
- Switch Coturn to `SELECT value FROM turn_secret WHERE realm = $1`.
- Final primary key: `(realm, value)`.
- Final FK: `turn_secret.realm -> tenants.realm ON DELETE CASCADE`.
- At most one row per realm may have `valid_until IS NULL`.
- Credential issuance signs only with the current row.
- Secret lifecycle changes serialize on a pessimistic lock of the stable tenant row.
- Flush old-row expiry before replacement INSERT.
- Expired grace rows are physically deleted.
- Checked-in cleanup interval is <= 10 seconds.
- All behavioral changes follow RED → minimal GREEN → regression → commit.
- Before Java symbol edits, follow `AGENTS.md`: run GitNexus impact analysis when available and report HIGH/CRITICAL impact before editing.
- Before commits, run the repository-required GitNexus change detection when available.
- Stage explicit task-owned paths only; never use `git add .`, `git add -A`, or `git add --all`.

---

## File Map

```text
src/main/resources/db/migration/
└── V5__refactor_turn_secret_multi_secret.sql

src/main/java/com/k2iot/turncred/
├── TurnCredentialPlatformApplication.java
├── credential/
│   └── TurnCredentialService.java
├── tenant/
│   └── TenantRepository.java
└── secret/
    ├── TurnSecret.java
    ├── TurnSecretId.java
    ├── TurnSecretRepository.java
    ├── SecretRotationService.java
    └── ExpiredTurnSecretCleanupJob.java

src/main/resources/application.yml
coturn/turnserver.conf

src/test/java/com/k2iot/turncred/
├── credential/TurnCredentialServiceTest.java
├── integration/
│   ├── TurnSecretMigrationTest.java
│   └── CoturnTurnSecretContractTest.java
└── secret/
    ├── TurnSecretRepositoryTest.java
    ├── SecretRotationServiceTest.java
    ├── SecretRotationConcurrencyTest.java
    └── ExpiredTurnSecretCleanupJobTest.java
```

The schema/JPA/repository/issuance/rotation/Coturn-query changes form one compatibility boundary and are committed together in Task 1 so no commit leaves V5 incompatible with the application or Coturn config.

---

### Task 1: Replace V4 view-based rotation with V5 multi-row persistence

**Files:**
- Create: `src/main/resources/db/migration/V5__refactor_turn_secret_multi_secret.sql`
- Create: `src/main/java/com/k2iot/turncred/secret/TurnSecretId.java`
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecret.java`
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java`
- Modify: `src/main/java/com/k2iot/turncred/tenant/TenantRepository.java`
- Modify: `src/main/java/com/k2iot/turncred/secret/SecretRotationService.java`
- Modify: `src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java`
- Modify: `coturn/turnserver.conf`
- Create: `src/test/java/com/k2iot/turncred/integration/TurnSecretMigrationTest.java`
- Create: `src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java`
- Create: `src/test/java/com/k2iot/turncred/secret/SecretRotationServiceTest.java`
- Modify: `src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java`

**Interfaces:**
- Produces: `TurnSecretId(String realm, String value)`.
- Produces: `Optional<TurnSecret> TurnSecretRepository.findCurrentByRealm(String realm)`.
- Produces: `List<TurnSecret> TurnSecretRepository.findAllByRealm(String realm)`.
- Produces: `Optional<Tenant> TenantRepository.findByRealmForUpdate(String realm)`.
- Preserves: `SecretRotationService.createInitialSecret(String)`.
- Preserves: `SecretRotationService.rotate(String, Duration)`.
- Preserves: `TurnCredentialService.issueCredential(Tenant, String)`.
- Removes: `TurnSecretRepository.findByRealm(String): Optional<TurnSecret>`.

- [ ] **Step 1: Write migration RED tests against V4 state**

Create `TurnSecretMigrationTest` with PostgreSQL Testcontainers and programmatic Flyway.

Use these helpers:

```java
private void resetAndMigrateToV4() {
    Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .cleanDisabled(false)
            .target("4")
            .load();
    flyway.clean();
    flyway.migrate();
}

private void migrateToLatest() {
    Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
}

private Connection connection() throws SQLException {
    return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
}
```

Add current + valid previous preservation:

```java
@Test
void migratesCurrentAndUnexpiredPreviousSecretIntoSeparateRows() throws Exception {
    resetAndMigrateToV4();

    try (Connection c = connection()) {
        c.createStatement().executeUpdate("""
            INSERT INTO tenants (name, realm, api_key_hash)
            VALUES ('Acme', 'acme.example.com', 'hash-acme')
            """);

        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO turn_secret
                (realm, value, previous_value, previous_valid_until, rotated_at)
            VALUES (?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, "acme.example.com");
            ps.setString(2, "secret-v2");
            ps.setString(3, "secret-v1");
            ps.setObject(4, Instant.now().plusSeconds(3600));
            ps.setObject(5, Instant.now().minusSeconds(60));
            ps.executeUpdate();
        }
    }

    migrateToLatest();

    try (Connection c = connection();
         PreparedStatement ps = c.prepareStatement("""
             SELECT value, valid_until
             FROM turn_secret
             WHERE realm = ?
             ORDER BY value
             """)) {
        ps.setString(1, "acme.example.com");
        try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("value")).isEqualTo("secret-v1");
            assertThat(rs.getObject("valid_until")).isNotNull();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("value")).isEqualTo("secret-v2");
            assertThat(rs.getObject("valid_until")).isNull();
            assertThat(rs.next()).isFalse();
        }
    }
}
```

Add expired legacy previous coverage:

```java
@Test
void doesNotMigrateExpiredPreviousSecret() throws Exception {
    resetAndMigrateToV4();

    try (Connection c = connection()) {
        c.createStatement().executeUpdate("""
            INSERT INTO tenants (name, realm, api_key_hash)
            VALUES ('Expired', 'expired.example.com', 'hash-expired')
            """);
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO turn_secret
                (realm, value, previous_value, previous_valid_until)
            VALUES (?, ?, ?, ?)
            """)) {
            ps.setString(1, "expired.example.com");
            ps.setString(2, "secret-current");
            ps.setString(3, "secret-old");
            ps.setObject(4, Instant.now().minusSeconds(60));
            ps.executeUpdate();
        }
    }

    migrateToLatest();

    try (Connection c = connection();
         PreparedStatement ps = c.prepareStatement(
                 "SELECT value FROM turn_secret WHERE realm = ?")) {
        ps.setString(1, "expired.example.com");
        try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("secret-current");
            assertThat(rs.next()).isFalse();
        }
    }
}
```

Add ambiguous-data rollback coverage:

```java
@Test
void rejectsPreviousSecretWithoutExpiryAndKeepsV4Schema() throws Exception {
    resetAndMigrateToV4();

    try (Connection c = connection()) {
        c.createStatement().executeUpdate("""
            INSERT INTO tenants (name, realm, api_key_hash)
            VALUES ('Unsafe', 'unsafe.example.com', 'hash-unsafe')
            """);
        c.createStatement().executeUpdate("""
            INSERT INTO turn_secret (realm, value, previous_value, previous_valid_until)
            VALUES ('unsafe.example.com', 'current', 'unsafe-old', NULL)
            """);
    }

    assertThatThrownBy(this::migrateToLatest)
            .isInstanceOf(FlywayException.class);

    try (Connection c = connection();
         ResultSet rs = c.createStatement().executeQuery("""
             SELECT previous_value
             FROM turn_secret
             WHERE realm = 'unsafe.example.com'
             """)) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("unsafe-old");
    }
}
```

Add V4-view removal and cascade coverage:

```java
@Test
void removesActiveViewAndRecreatesCascadeForeignKey() throws Exception {
    resetAndMigrateToV4();

    try (Connection c = connection()) {
        c.createStatement().executeUpdate("""
            INSERT INTO tenants (name, realm, api_key_hash)
            VALUES ('Cascade', 'cascade.example.com', 'hash-cascade')
            """);
        c.createStatement().executeUpdate("""
            INSERT INTO turn_secret (realm, value)
            VALUES ('cascade.example.com', 'secret-current')
            """);
    }

    migrateToLatest();

    try (Connection c = connection()) {
        try (ResultSet rs = c.createStatement().executeQuery("""
            SELECT to_regclass('public.turn_secret_active')
            """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNull();
        }

        c.createStatement().executeUpdate(
                "DELETE FROM tenants WHERE realm = 'cascade.example.com'");

        try (ResultSet rs = c.createStatement().executeQuery("""
            SELECT count(*) FROM turn_secret
            WHERE realm = 'cascade.example.com'
            """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }
}
```

- [ ] **Step 2: Write repository RED tests**

Create `TurnSecretRepositoryTest` as a PostgreSQL Testcontainers-backed `@DataJpaTest`.

Define local helpers:

```java
private Tenant createTenant(String realm) {
    Tenant tenant = new Tenant();
    tenant.setName("Tenant " + realm);
    tenant.setRealm(realm);
    tenant.setApiKeyHash("hash-" + UUID.randomUUID());
    tenant.setStatus(TenantStatus.ACTIVE);
    tenant.setCredentialTtlSec(3600);
    tenant.setRateLimitPerMin(600);
    return tenantRepository.saveAndFlush(tenant);
}

private TurnSecret secret(String realm, String value, Instant validUntil) {
    TurnSecret secret = new TurnSecret();
    secret.setRealm(realm);
    secret.setValue(value);
    secret.setValidUntil(validUntil);
    secret.setCreatedAt(Instant.now());
    return secret;
}
```

Add:

```java
@Test
void supportsMultipleRowsAndSelectsOnlyCurrentSecret() {
    String realm = "multi.example.com";
    createTenant(realm);

    repository.save(secret(realm, "secret-v1", Instant.now().plusSeconds(900)));
    repository.save(secret(realm, "secret-v2", null));
    repository.flush();

    assertThat(repository.findAllByRealm(realm))
            .extracting(TurnSecret::getValue)
            .containsExactlyInAnyOrder("secret-v1", "secret-v2");
    assertThat(repository.findCurrentByRealm(realm))
            .get()
            .extracting(TurnSecret::getValue)
            .isEqualTo("secret-v2");
}

@Test
void rejectsSecondCurrentRowForSameRealm() {
    String realm = "unique-current.example.com";
    createTenant(realm);

    repository.saveAndFlush(secret(realm, "secret-a", null));
    repository.save(secret(realm, "secret-b", null));

    assertThatThrownBy(repository::flush)
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void loadsRowsByCompositeIdentity() {
    String realm = "identity.example.com";
    createTenant(realm);

    repository.save(secret(realm, "secret-old", Instant.now().plusSeconds(900)));
    repository.saveAndFlush(secret(realm, "secret-current", null));

    assertThat(repository.findById(new TurnSecretId(realm, "secret-old"))).isPresent();
    assertThat(repository.findById(new TurnSecretId(realm, "secret-current"))).isPresent();
}
```

- [ ] **Step 3: Write secret lifecycle RED tests**

Create `SecretRotationServiceTest` using real PostgreSQL repositories with `@DataJpaTest` and `@Import(SecretRotationService.class)`.

Add:

```java
@Test
void rotateExpiresOldRowAndCreatesReplacementCurrent() {
    String realm = "rotate.example.com";
    createTenant(realm);

    String oldValue = service.createInitialSecret(realm);
    service.rotate(realm, Duration.ofMinutes(15));

    List<TurnSecret> rows = secretRepository.findAllByRealm(realm);
    assertThat(rows).hasSize(2);

    TurnSecret old = rows.stream()
            .filter(row -> row.getValue().equals(oldValue))
            .findFirst()
            .orElseThrow();
    assertThat(old.getValidUntil()).isAfter(Instant.now());

    TurnSecret current = secretRepository.findCurrentByRealm(realm).orElseThrow();
    assertThat(current.getValue()).isNotEqualTo(oldValue);
    assertThat(current.getValidUntil()).isNull();
}

@Test
void repeatedRotationKeepsMultipleGraceRowsAndOneCurrent() {
    String realm = "repeat.example.com";
    createTenant(realm);

    service.createInitialSecret(realm);
    service.rotate(realm, Duration.ofMinutes(30));
    service.rotate(realm, Duration.ofMinutes(15));

    List<TurnSecret> rows = secretRepository.findAllByRealm(realm);
    assertThat(rows).hasSize(3);
    assertThat(rows.stream().filter(row -> row.getValidUntil() == null).count())
            .isEqualTo(1);
    assertThat(rows.stream().filter(row -> row.getValidUntil() != null).count())
            .isEqualTo(2);
}

@Test
void rejectsInvalidGraceWindow() {
    String realm = "invalid-grace.example.com";
    createTenant(realm);
    service.createInitialSecret(realm);

    assertThatThrownBy(() -> service.rotate(realm, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.rotate(realm, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void secondInitialSecretIsRejected() {
    String realm = "initial.example.com";
    createTenant(realm);
    service.createInitialSecret(realm);

    assertThatThrownBy(() -> service.createInitialSecret(realm))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 4: Update credential RED coverage**

In `TurnCredentialServiceTest`, replace every `findByRealm` stub with `findCurrentByRealm` and add:

```java
@Test
void signsWithCurrentSecretOnly() {
    Tenant tenant = tenantWithRealm("current-only.example.com");
    TurnSecret current = new TurnSecret();
    current.setRealm(tenant.getRealm());
    current.setValue("secret-v2");
    current.setValidUntil(null);
    current.setCreatedAt(Instant.now());

    when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
    when(secretRepository.findCurrentByRealm(tenant.getRealm()))
            .thenReturn(Optional.of(current));

    TurnCredential credential = service.issueCredential(tenant, "user-42");

    assertThat(credential.password())
            .isEqualTo(new HmacSigner().sign("secret-v2", credential.username()));
    verify(secretRepository).findCurrentByRealm(tenant.getRealm());
}
```

- [ ] **Step 5: Run RED**

```bash
./mvnw test -Dtest=TurnSecretMigrationTest,TurnSecretRepositoryTest,SecretRotationServiceTest,TurnCredentialServiceTest
```

Expected: compilation/test failures because V5, composite identity, current-secret repository APIs, and lifecycle fields do not exist yet.

- [ ] **Step 6: Implement V5**

Create `V5__refactor_turn_secret_multi_secret.sql`:

```sql
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM turn_secret
        WHERE previous_value IS NOT NULL
          AND previous_valid_until IS NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot migrate turn_secret: previous_value exists without previous_valid_until';
    END IF;
END
$$;

DROP VIEW turn_secret_active;

ALTER TABLE turn_secret
    DROP CONSTRAINT turn_secret_pkey,
    DROP CONSTRAINT turn_secret_realm_fkey;

ALTER TABLE turn_secret
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN created_at TIMESTAMPTZ;

UPDATE turn_secret
SET created_at = COALESCE(rotated_at, now());

INSERT INTO turn_secret (
    realm,
    value,
    previous_value,
    previous_valid_until,
    rotated_at,
    valid_until,
    created_at
)
SELECT
    realm,
    previous_value,
    NULL,
    NULL,
    rotated_at,
    previous_valid_until,
    COALESCE(rotated_at, now())
FROM turn_secret
WHERE previous_value IS NOT NULL
  AND previous_value <> value
  AND previous_valid_until > now();

ALTER TABLE turn_secret
    DROP COLUMN previous_value,
    DROP COLUMN previous_valid_until,
    DROP COLUMN rotated_at;

ALTER TABLE turn_secret
    ALTER COLUMN realm SET NOT NULL,
    ALTER COLUMN value TYPE VARCHAR(256),
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE turn_secret
    ADD CONSTRAINT turn_secret_pkey PRIMARY KEY (realm, value),
    ADD CONSTRAINT turn_secret_realm_fkey
        FOREIGN KEY (realm)
        REFERENCES tenants(realm)
        ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_turn_secret_current
    ON turn_secret(realm)
    WHERE valid_until IS NULL;

CREATE INDEX idx_turn_secret_expiry
    ON turn_secret(valid_until)
    WHERE valid_until IS NOT NULL;
```

- [ ] **Step 7: Implement composite JPA identity**

Create `TurnSecretId.java`:

```java
package com.k2iot.turncred.secret;

import java.io.Serializable;
import java.util.Objects;

public class TurnSecretId implements Serializable {
    private String realm;
    private String value;

    public TurnSecretId() {}

    public TurnSecretId(String realm, String value) {
        this.realm = realm;
        this.value = value;
    }

    public String getRealm() { return realm; }
    public String getValue() { return value; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TurnSecretId that)) return false;
        return Objects.equals(realm, that.realm)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, value);
    }
}
```

Replace `TurnSecret` with:

```java
@Entity
@Table(name = "turn_secret")
@IdClass(TurnSecretId.class)
public class TurnSecret {

    @Id
    @Column(nullable = false)
    private String realm;

    @Id
    @Column(nullable = false, length = 256)
    private String value;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 8: Implement repository contracts and stable tenant lock**

Replace `TurnSecretRepository` with:

```java
public interface TurnSecretRepository extends JpaRepository<TurnSecret, TurnSecretId> {

    @Query("""
        select s from TurnSecret s
        where s.realm = :realm
          and s.validUntil is null
        """)
    Optional<TurnSecret> findCurrentByRealm(@Param("realm") String realm);

    List<TurnSecret> findAllByRealm(String realm);
}
```

Add to `TenantRepository`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select t from Tenant t where t.realm = :realm")
Optional<Tenant> findByRealmForUpdate(@Param("realm") String realm);
```

- [ ] **Step 9: Implement current-only issuance and locked rotation**

In `TurnCredentialService`, replace:

```java
secretRepository.findByRealm(tenant.getRealm())
```

with:

```java
secretRepository.findCurrentByRealm(tenant.getRealm())
```

Refactor `SecretRotationService` to depend on both `TurnSecretRepository` and `TenantRepository`.

Use:

```java
@Transactional
public String createInitialSecret(String realm) {
    tenantRepository.findByRealmForUpdate(realm)
            .orElseThrow(() -> new IllegalStateException(
                    "Tenant not found for realm " + realm));

    if (turnSecretRepository.findCurrentByRealm(realm).isPresent()) {
        throw new IllegalStateException(
                "Current secret already exists for realm " + realm);
    }

    String value = generateSecret();
    TurnSecret secret = new TurnSecret();
    secret.setRealm(realm);
    secret.setValue(value);
    secret.setValidUntil(null);
    secret.setCreatedAt(Instant.now());
    turnSecretRepository.save(secret);
    return value;
}
```

Use this rotation sequence:

```java
@Transactional
public void rotate(String realm, Duration graceWindow) {
    if (graceWindow == null || graceWindow.isZero() || graceWindow.isNegative()) {
        throw new IllegalArgumentException("graceWindow must be positive");
    }

    tenantRepository.findByRealmForUpdate(realm)
            .orElseThrow(() -> new IllegalStateException(
                    "Tenant not found for realm " + realm));

    TurnSecret current = turnSecretRepository.findCurrentByRealm(realm)
            .orElseThrow(() -> new IllegalStateException(
                    "No current secret found for realm " + realm));

    Instant now = Instant.now();
    current.setValidUntil(now.plus(graceWindow));
    turnSecretRepository.saveAndFlush(current);

    TurnSecret replacement = new TurnSecret();
    replacement.setRealm(realm);
    replacement.setValue(generateSecret());
    replacement.setValidUntil(null);
    replacement.setCreatedAt(now);
    turnSecretRepository.save(replacement);
}
```

- [ ] **Step 10: Switch Coturn to direct table lookup**

Change `coturn/turnserver.conf` from:

```ini
userdb-user-secret-query="SELECT value FROM turn_secret_active WHERE realm = $1"
```

to:

```ini
userdb-user-secret-query="SELECT value FROM turn_secret WHERE realm = $1"
```

- [ ] **Step 11: Run GREEN and regressions**

```bash
./mvnw test -Dtest=TurnSecretMigrationTest,TurnSecretRepositoryTest,SecretRotationServiceTest,TurnCredentialServiceTest,TenantAdminControllerTest
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 12: Commit atomic compatibility boundary**

```bash
git add -- \
  src/main/resources/db/migration/V5__refactor_turn_secret_multi_secret.sql \
  src/main/java/com/k2iot/turncred/secret/TurnSecretId.java \
  src/main/java/com/k2iot/turncred/secret/TurnSecret.java \
  src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java \
  src/main/java/com/k2iot/turncred/tenant/TenantRepository.java \
  src/main/java/com/k2iot/turncred/secret/SecretRotationService.java \
  src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java \
  coturn/turnserver.conf \
  src/test/java/com/k2iot/turncred/integration/TurnSecretMigrationTest.java \
  src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java \
  src/test/java/com/k2iot/turncred/secret/SecretRotationServiceTest.java \
  src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java
git commit -m "refactor: support multiple TURN secrets per realm"
```

---

### Task 2: Prove concurrent rotations serialize correctly

**Files:**
- Create: `src/test/java/com/k2iot/turncred/secret/SecretRotationConcurrencyTest.java`

**Interfaces:**
- Verifies `TenantRepository.findByRealmForUpdate` is the stable per-realm serialization point.

- [ ] **Step 1: Write the concurrency test**

Use `@SpringBootTest` with PostgreSQL Testcontainers and two executor threads.

Test sequence:

```java
@Test
void concurrentRotationsSerializeAndLeaveExactlyOneCurrentSecret() throws Exception {
    String realm = "concurrent.example.com";
    createTenant(realm);
    rotationService.createInitialSecret(realm);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);

    Callable<Void> rotate = () -> {
        start.await();
        rotationService.rotate(realm, Duration.ofMinutes(15));
        return null;
    };

    Future<Void> first = executor.submit(rotate);
    Future<Void> second = executor.submit(rotate);
    start.countDown();

    first.get(10, TimeUnit.SECONDS);
    second.get(10, TimeUnit.SECONDS);
    executor.shutdownNow();

    List<TurnSecret> rows = secretRepository.findAllByRealm(realm);
    assertThat(rows).hasSize(3);
    assertThat(rows.stream().filter(row -> row.getValidUntil() == null).count())
            .isEqualTo(1);
    assertThat(rows.stream().filter(row -> row.getValidUntil() != null).count())
            .isEqualTo(2);
}
```

The test must use real Spring transactions around `SecretRotationService`; do not mock repositories.

- [ ] **Step 2: Run test**

```bash
./mvnw test -Dtest=SecretRotationConcurrencyTest
```

Expected: PASS. If it deadlocks, loses one rotation, or leaves two current rows, fix the locking/flush sequence rather than weakening assertions.

- [ ] **Step 3: Run full suite**

```bash
./mvnw test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -- src/test/java/com/k2iot/turncred/secret/SecretRotationConcurrencyTest.java
git commit -m "test: cover concurrent TURN secret rotation"
```

---

### Task 3: Add physical cleanup for expired grace rows

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java`
- Create: `src/main/java/com/k2iot/turncred/secret/ExpiredTurnSecretCleanupJob.java`
- Modify: `src/main/java/com/k2iot/turncred/TurnCredentialPlatformApplication.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/k2iot/turncred/secret/ExpiredTurnSecretCleanupJobTest.java`

**Interfaces:**
- Produces: `int TurnSecretRepository.deleteExpired(Instant cutoff)`.
- Produces: `int ExpiredTurnSecretCleanupJob.cleanupExpiredSecrets()`.
- Produces: scheduled cleanup configured by `turn.secret.cleanup-interval-ms`.

- [ ] **Step 1: Write cleanup RED test**

Create a real PostgreSQL repository test with one expired row, one future grace row, and one current row:

```java
@Test
void cleanupDeletesOnlyExpiredGraceRows() {
    String realm = "cleanup.example.com";
    createTenant(realm);

    secretRepository.save(secret(realm, "expired", Instant.now().minusSeconds(1)));
    secretRepository.save(secret(realm, "future", Instant.now().plusSeconds(3600)));
    secretRepository.saveAndFlush(secret(realm, "current", null));

    int deleted = cleanupJob.cleanupExpiredSecrets();

    assertThat(deleted).isEqualTo(1);
    assertThat(secretRepository.findAllByRealm(realm))
            .extracting(TurnSecret::getValue)
            .containsExactlyInAnyOrder("future", "current");
}
```

- [ ] **Step 2: Run RED**

```bash
./mvnw test -Dtest=ExpiredTurnSecretCleanupJobTest
```

Expected: FAIL because cleanup APIs do not exist.

- [ ] **Step 3: Add repository bulk delete**

```java
@Modifying
@Query("""
    delete from TurnSecret s
    where s.validUntil is not null
      and s.validUntil <= :cutoff
    """)
int deleteExpired(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 4: Add scheduled job**

```java
package com.k2iot.turncred.secret;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ExpiredTurnSecretCleanupJob {

    private final TurnSecretRepository repository;

    public ExpiredTurnSecretCleanupJob(TurnSecretRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${turn.secret.cleanup-interval-ms:10000}")
    @Transactional
    public int cleanupExpiredSecrets() {
        return repository.deleteExpired(Instant.now());
    }
}
```

Add `@EnableScheduling` to `TurnCredentialPlatformApplication`.

Configure:

```yaml
turn:
  secret:
    cleanup-interval-ms: 10000
```

Preserve all existing sibling `turn:` properties in `application.yml`; merge this nested property instead of replacing the existing block.

- [ ] **Step 5: Run GREEN and regression**

```bash
./mvnw test -Dtest=ExpiredTurnSecretCleanupJobTest,TurnSecretRepositoryTest
./mvnw test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -- \
  src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java \
  src/main/java/com/k2iot/turncred/secret/ExpiredTurnSecretCleanupJob.java \
  src/main/java/com/k2iot/turncred/TurnCredentialPlatformApplication.java \
  src/main/resources/application.yml \
  src/test/java/com/k2iot/turncred/secret/ExpiredTurnSecretCleanupJobTest.java
git commit -m "feat: clean expired TURN secret rows"
```

---

### Task 4: Lock the Coturn-facing raw database contract

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/CoturnTurnSecretContractTest.java`

**Interfaces:**
- Verifies the exact raw surface Coturn now uses: `SELECT value FROM turn_secret WHERE realm = ?`.

- [ ] **Step 1: Write contract test**

With PostgreSQL Testcontainers, insert one future grace row and one current row, then query exactly as Coturn does:

```java
@Test
void rawRealmLookupSeesGraceAndCurrentThenOnlyCurrentAfterCleanup() {
    String realm = "coturn-contract.example.com";
    createTenant(realm);

    secretRepository.save(secret(realm, "secret-v1", Instant.now().plusSeconds(3600)));
    secretRepository.saveAndFlush(secret(realm, "secret-v2", null));

    List<String> duringGrace = jdbcTemplate.queryForList(
            "SELECT value FROM turn_secret WHERE realm = ?",
            String.class,
            realm);

    assertThat(duringGrace)
            .containsExactlyInAnyOrder("secret-v1", "secret-v2");

    jdbcTemplate.update(
            "UPDATE turn_secret SET valid_until = now() - interval '1 second' "
                    + "WHERE realm = ? AND value = ?",
            realm,
            "secret-v1");

    cleanupJob.cleanupExpiredSecrets();

    List<String> afterCleanup = jdbcTemplate.queryForList(
            "SELECT value FROM turn_secret WHERE realm = ?",
            String.class,
            realm);

    assertThat(afterCleanup).containsExactly("secret-v2");
}
```

The SELECT intentionally contains no `valid_until` filter.

- [ ] **Step 2: Run contract and full suite**

```bash
./mvnw test -Dtest=CoturnTurnSecretContractTest
./mvnw test
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -- src/test/java/com/k2iot/turncred/integration/CoturnTurnSecretContractTest.java
git commit -m "test: lock Coturn multi-secret database contract"
```

---

### Task 5: Align parent docs and perform release-level verification

**Files:**
- Modify: `docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md`
- Modify: `docs/superpowers/plans/2026-08-21-code-review-remediation-plan.md`
- Modify if needed: `README.md`

**Interfaces:**
- Makes the 2026-08-21 multi-secret spec the active source of truth and marks the V4 view solution as superseded by V5 multi-row persistence.

- [ ] **Step 1: Find stale assumptions**

```bash
git grep -n -E 'previous_value|previousValue|previous_valid_until|previousValidUntil|turn_secret_active|findByRealm\(' -- .
```

Expected after implementation:

- no production Java use of legacy previous-secret fields;
- no production Coturn query of `turn_secret_active`;
- no credential issuance use of `TurnSecretRepository.findByRealm`;
- legacy names remain only in V5 migration/migration tests and historical docs explicitly marked superseded.

- [ ] **Step 2: Align documentation**

Update the parent design to point to:

```text
docs/superpowers/specs/2026-08-21-turn-secret-multi-secret-rotation-design.md
```

Update the code-review remediation plan Task 1 with a note that its V4 view was implemented but is superseded by V5 multi-row persistence; do not rewrite historical V4 migration content.

If README describes the view or `previous_value` rotation model, update it to:

```text
One realm may have multiple turn_secret rows. The current row has
valid_until IS NULL. Grace-period rows remain directly visible to
Coturn until they expire and the cleanup job physically removes them.
```

- [ ] **Step 3: Verify fresh V1 → V5 startup**

```bash
docker compose down -v
docker compose up -d postgres redis
./mvnw spring-boot:run
```

Expected:

- Flyway applies V1, V2, V3, V4, then V5.
- Hibernate schema validation succeeds.
- Application starts.

Inspect:

```sql
\d turn_secret
```

Expected logical shape:

```text
realm       varchar(255) not null
value       varchar(256) not null
valid_until timestamptz
created_at  timestamptz not null
PRIMARY KEY (realm, value)
FOREIGN KEY (realm) REFERENCES tenants(realm) ON DELETE CASCADE
UNIQUE INDEX uq_turn_secret_current ON realm WHERE valid_until IS NULL
INDEX idx_turn_secret_expiry ON valid_until WHERE valid_until IS NOT NULL
```

Also verify:

```sql
SELECT to_regclass('public.turn_secret_active');
```

Expected: `NULL`.

- [ ] **Step 4: Verify full stack Coturn config**

```bash
./mvnw clean package -DskipTests
docker compose up -d --build
```

Inspect the running Coturn configuration/logs and confirm the configured query references `turn_secret`, not `turn_secret_active`.

- [ ] **Step 5: Final regression and hygiene**

```bash
./mvnw test
git diff --check
git status --short
```

Expected: all tests pass, `git diff --check` emits no output, and only intended files remain changed.

When GitNexus is available, run `detect_changes()` against the intended base and verify the blast radius is limited to TURN secret persistence, issuance, rotation, cleanup, Coturn configuration, tests, and related documentation.

- [ ] **Step 6: Commit documentation alignment**

Stage only files actually changed:

```bash
git add -- \
  docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md \
  docs/superpowers/plans/2026-08-21-code-review-remediation-plan.md \
  README.md
git commit -m "docs: align platform with multi-secret TURN rotation"
```

If README did not change, omit it from `git add`. Do not create an empty commit.

---

## Final Verification Checklist

- [ ] Work is performed on `feat/turn-credential-platform`.
- [ ] V1-V4 remain unchanged.
- [ ] V5 performs the multi-row migration.
- [ ] V5 drops `turn_secret_active` before removing legacy columns.
- [ ] Current legacy secrets survive migration.
- [ ] Still-valid previous secrets become independent rows.
- [ ] Expired previous secrets are not migrated.
- [ ] Ambiguous previous secrets with no expiry abort V5 transactionally.
- [ ] `turn_secret` PK is `(realm, value)`.
- [ ] Tenant FK is recreated with `ON DELETE CASCADE`.
- [ ] `uq_turn_secret_current` permits at most one current row per realm.
- [ ] `TurnSecret` uses `TurnSecretId` composite identity.
- [ ] `TurnSecretRepository.findByRealm(String): Optional<TurnSecret>` is removed.
- [ ] Credential issuance uses `findCurrentByRealm` only.
- [ ] Lifecycle methods lock `tenants.realm` pessimistically.
- [ ] Rotation flushes the old-row expiry before replacement INSERT.
- [ ] Concurrent same-realm rotations finish with exactly one current row.
- [ ] Coturn queries `turn_secret` directly.
- [ ] Cleanup physically deletes expired rows within <=10 seconds under checked-in config.
- [ ] Raw Coturn lookup sees current + grace rows during grace and only current after cleanup.
- [ ] Existing REST API shapes remain unchanged.
- [ ] `./mvnw test` passes.
- [ ] Fresh V1 → V5 startup passes Hibernate validation.
- [ ] `turn_secret_active` no longer exists after V5.
- [ ] `git diff --check` passes.
- [ ] GitNexus impact/change checks are recorded when the environment provides them.
