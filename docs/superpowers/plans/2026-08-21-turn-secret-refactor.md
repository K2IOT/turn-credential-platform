# TURN Secret Storage Refactoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `turn_secret` from one-row-per-realm to multi-row-per-realm (composite PK `(realm, value)`, `valid_until IS NULL` = current secret) while preserving all existing data and keeping Coturn read-compatible.

**Architecture:** New `TurnSecretId` embeddable composite PK; `TurnSecret` entity drops `previousValue`/`previousValidUntil`/`rotatedAt`, adds `validUntil`/`createdAt`; `TurnSecretRepository` replaces `findByRealm` with `findCurrentByRealm` + `findValidByRealm` + `deleteExpiredForRealm`; `SecretRotationService.rotate()` becomes `@Transactional` (cleanup → mark expiry → insert new); Flyway V5 migration preserves data. No API contract changes.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, PostgreSQL 16, Flyway, JUnit 5, Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-21-turn-secret-refactor.md`

## Global Constraints

- Java 21. Maven build: `./mvnw test` must be green before every commit.
- `./mvnw test` also runs Testcontainers-based tests — Docker must be running.
- `@Transactional` required on `rotate()` — the partial unique index enforces the invariant at DB level.
- `findByRealm(String)` MUST be removed entirely; all callers use `findCurrentByRealm`.
- No Lombok. Plain getters/setters.
- Timestamps: `Instant` in Java, `TIMESTAMPTZ` in Postgres.
- Each task ends with a green `./mvnw test` and a commit.

---

## File Structure

```
src/main/java/com/k2iot/turncred/secret/
  TurnSecretId.java               <- NEW
  TurnSecret.java                 <- REWRITE
  TurnSecretRepository.java       <- REWRITE
  SecretRotationService.java      <- REWRITE

src/main/resources/db/migration/
  V5__refactor_turn_secret.sql    <- NEW

src/test/java/com/k2iot/turncred/
  secret/
    TurnSecretRepositoryTest.java   <- NEW
    SecretRotationServiceTest.java  <- REWRITE
  credential/
    TurnCredentialServiceTest.java  <- UPDATE (mock stub only)
  integration/
    CredentialIssuanceIntegrationTest.java  <- UPDATE (add assertions)
```

---

### Task 1: Flyway V5 migration (schema + data migration)

**Files:**
- Create: `src/main/resources/db/migration/V5__refactor_turn_secret.sql`

**Interfaces:**
- Produces: new `turn_secret` schema with `PRIMARY KEY (realm, value)`, `valid_until`, `created_at`; data from old schema preserved; `turn_secret_old` dropped; `uq_turn_secret_current` partial unique index created.
- Consumes: existing `turn_secret` with columns `realm`, `value`, `previous_value`, `previous_valid_until`, `rotated_at`.

- [ ] **Step 1: Write V5__refactor_turn_secret.sql**

```sql
-- V5: Refactor turn_secret to multi-row-per-realm model
-- All DDL is transactional in PostgreSQL; Flyway wraps this in a single transaction.

-- 1. Drop the active view (V4) -- no application code queries it
DROP VIEW IF EXISTS turn_secret_active;

-- 2. Rename old table to preserve data during migration
ALTER TABLE turn_secret RENAME TO turn_secret_old;

-- 3. Create new schema
CREATE TABLE turn_secret (
    realm       VARCHAR(127) NOT NULL,
    value       VARCHAR(256) NOT NULL,
    valid_until TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (realm, value),

    CONSTRAINT fk_turn_secret_tenant
        FOREIGN KEY (realm)
        REFERENCES tenants(realm)
        ON DELETE CASCADE
);

-- 4. Migrate current secrets (valid_until = NULL)
INSERT INTO turn_secret (realm, value, valid_until, created_at)
SELECT realm, value, NULL, rotated_at
FROM turn_secret_old;

-- 5. Migrate still-valid previous secrets
INSERT INTO turn_secret (realm, value, valid_until, created_at)
SELECT realm, previous_value, previous_valid_until, rotated_at
FROM turn_secret_old
WHERE previous_value IS NOT NULL
  AND previous_valid_until IS NOT NULL
  AND previous_valid_until > now();

-- 6. Indexes
CREATE UNIQUE INDEX uq_turn_secret_current
    ON turn_secret(realm)
    WHERE valid_until IS NULL;

CREATE INDEX idx_turn_secret_valid_until
    ON turn_secret(valid_until)
    WHERE valid_until IS NOT NULL;

-- 7. Drop old table
DROP TABLE turn_secret_old;
```

- [ ] **Step 2: Verify migration applies against a fresh Testcontainers DB**

Run:
```bash
./mvnw test -Dtest=CredentialIssuanceIntegrationTest
```
Expected: PASS (integration test spins up a Postgres container and applies all Flyway migrations V1-V5).
If it fails with a Hibernate schema validation error, it means the JPA entity (Task 2) hasn't been updated yet — that is expected at this point if you run this before Task 2. Skip this verification until after Task 2.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V5__refactor_turn_secret.sql
git commit -m "feat(db): V5 migration — refactor turn_secret to multi-row-per-realm model"
```

---

### Task 2: TurnSecretId embeddable + TurnSecret entity rewrite

**Files:**
- Create: `src/main/java/com/k2iot/turncred/secret/TurnSecretId.java`
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecret.java`

**Interfaces:**
- Produces:
  - `TurnSecretId(String realm, String value)` — composite PK embeddable.
  - `TurnSecret(TurnSecretId id)` — entity with `validUntil` (nullable Instant) and `createdAt`.
  - `TurnSecret.getRealm(): String`, `TurnSecret.getValue(): String`, `TurnSecret.getValidUntil(): Instant`, `TurnSecret.setValidUntil(Instant)`.
- Consumed by: Task 3 (repository), Task 4 (service).

- [ ] **Step 1: Create TurnSecretId.java**

```java
package com.k2iot.turncred.secret;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TurnSecretId implements Serializable {

    private String realm;
    private String value;

    public TurnSecretId() {}

    public TurnSecretId(String realm, String value) {
        this.realm = realm;
        this.value = value;
    }

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TurnSecretId that)) return false;
        return Objects.equals(realm, that.realm) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, value);
    }
}
```

- [ ] **Step 2: Rewrite TurnSecret.java**

Replace the entire file contents:

```java
package com.k2iot.turncred.secret;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "turn_secret")
public class TurnSecret {

    @EmbeddedId
    private TurnSecretId id;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TurnSecret() {}

    public TurnSecret(TurnSecretId id) {
        this.id = id;
    }

    public TurnSecretId getId() { return id; }
    public void setId(TurnSecretId id) { this.id = id; }

    public String getRealm() { return id.getRealm(); }
    public String getValue() { return id.getValue(); }

    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: Verify compile**

Run:
```bash
./mvnw compile
```
Expected: BUILD SUCCESS with zero errors.

Note: `TurnSecretRepository` and `SecretRotationService` will fail to compile until Tasks 3 and 4 are complete — do those tasks in sequence immediately after this one.

- [ ] **Step 4: Commit (after Tasks 3 and 4 compile — see note)**

```bash
git add src/main/java/com/k2iot/turncred/secret/TurnSecretId.java
git add src/main/java/com/k2iot/turncred/secret/TurnSecret.java
git commit -m "feat(secret): replace TurnSecret entity with composite PK (realm, value) + validUntil"
```

---

### Task 3: TurnSecretRepository rewrite

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java`
- Create: `src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java`

**Interfaces:**
- Consumes: `TurnSecret` (Task 2), `TurnSecretId` (Task 2).
- Produces:
  - `findCurrentByRealm(String realm): Optional<TurnSecret>` — returns row where `validUntil IS NULL`.
  - `findValidByRealm(String realm): List<TurnSecret>` — returns current + grace-period rows.
  - `deleteExpiredForRealm(String realm): void` — deletes rows where `validUntil <= now()`.
  - `findByRealm(String)` is REMOVED.

- [ ] **Step 1: Write the failing repository test**

```java
package com.k2iot.turncred.secret;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TurnSecretRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TurnSecretRepository repo;

    private void saveSecret(String realm, String value, Instant validUntil) {
        TurnSecret s = new TurnSecret(new TurnSecretId(realm, value));
        s.setValidUntil(validUntil);
        repo.save(s);
    }

    @Test
    void findCurrentByRealmReturnsNullValidUntilRowOnly() {
        saveSecret("r1", "current-val", null);
        saveSecret("r1", "grace-val", Instant.now().plusSeconds(3600));

        Optional<TurnSecret> found = repo.findCurrentByRealm("r1");

        assertThat(found).isPresent();
        assertThat(found.get().getValue()).isEqualTo("current-val");
        assertThat(found.get().getValidUntil()).isNull();
    }

    @Test
    void findCurrentByRealmReturnsEmptyWhenNoCurrentSecret() {
        saveSecret("r2", "grace-val", Instant.now().plusSeconds(3600));

        Optional<TurnSecret> found = repo.findCurrentByRealm("r2");

        assertThat(found).isEmpty();
    }

    @Test
    void findValidByRealmExcludesExpiredRows() {
        saveSecret("r3", "current-val", null);
        saveSecret("r3", "grace-val", Instant.now().plusSeconds(3600));
        saveSecret("r3", "expired-val", Instant.now().minusSeconds(1));

        List<TurnSecret> valid = repo.findValidByRealm("r3");

        assertThat(valid).hasSize(2);
        assertThat(valid.stream().map(TurnSecret::getValue))
                .containsExactlyInAnyOrder("current-val", "grace-val");
    }

    @Test
    void deleteExpiredForRealmDeletesOnlyPastRows() {
        saveSecret("r4", "current-val", null);
        saveSecret("r4", "expired-val", Instant.now().minusSeconds(1));

        repo.deleteExpiredForRealm("r4");
        repo.flush();

        List<TurnSecret> remaining = repo.findAll();
        assertThat(remaining.stream().map(TurnSecret::getValue))
                .contains("current-val")
                .doesNotContain("expired-val");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=TurnSecretRepositoryTest`
Expected: FAIL — compile error, `TurnSecretRepository` has wrong interface or missing methods.

- [ ] **Step 3: Rewrite TurnSecretRepository.java**

```java
package com.k2iot.turncred.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TurnSecretRepository extends JpaRepository<TurnSecret, TurnSecretId> {

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

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=TurnSecretRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java
git add src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java
git commit -m "feat(secret): rewrite TurnSecretRepository with findCurrentByRealm, findValidByRealm, deleteExpiredForRealm"
```

---

### Task 4: SecretRotationService rewrite

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/secret/SecretRotationService.java`
- Modify: `src/test/java/com/k2iot/turncred/secret/SecretRotationServiceTest.java`

**Interfaces:**
- Consumes: `TurnSecretRepository.findCurrentByRealm`, `deleteExpiredForRealm`, `save` (Tasks 2+3).
- Produces: `createInitialSecret(String realm): String` (saves new TurnSecret with null validUntil); `rotate(String realm, Duration graceWindow)` (atomic: cleanup → mark expiry → insert new).

- [ ] **Step 1: Write the failing service tests**

```java
package com.k2iot.turncred.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecretRotationServiceTest {

    @Mock
    private TurnSecretRepository secretRepository;

    private SecretRotationService rotationService;

    @BeforeEach
    void setUp() {
        rotationService = new SecretRotationService(secretRepository);
    }

    @Test
    void createInitialSecretSavesNewRowWithNullValidUntil() {
        String realm = "test.realm.com";

        String value = rotationService.createInitialSecret(realm);

        assertThat(value).isNotBlank();
        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository).save(captor.capture());
        TurnSecret saved = captor.getValue();
        assertThat(saved.getRealm()).isEqualTo(realm);
        assertThat(saved.getValue()).isEqualTo(value);
        assertThat(saved.getValidUntil()).isNull();
    }

    @Test
    void rotateMarksPreviousSecretExpiringAndInsertsNewCurrent() {
        String realm = "test.realm.com";
        TurnSecret current = new TurnSecret(new TurnSecretId(realm, "old-secret"));

        when(secretRepository.findCurrentByRealm(realm)).thenReturn(Optional.of(current));

        rotationService.rotate(realm, Duration.ofMinutes(15));

        // Verify cleanup ran
        verify(secretRepository).deleteExpiredForRealm(realm);

        // Capture all save calls
        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository, times(2)).save(captor.capture());
        List<TurnSecret> saved = captor.getAllValues();

        // First save: old secret gets validUntil set
        TurnSecret expiring = saved.get(0);
        assertThat(expiring.getValue()).isEqualTo("old-secret");
        assertThat(expiring.getValidUntil()).isAfter(Instant.now());

        // Second save: new current secret with null validUntil
        TurnSecret newSecret = saved.get(1);
        assertThat(newSecret.getValue()).isNotEqualTo("old-secret");
        assertThat(newSecret.getValidUntil()).isNull();
    }

    @Test
    void rotateThrowsWhenNoCurrentSecretExists() {
        when(secretRepository.findCurrentByRealm("missing.realm")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rotationService.rotate("missing.realm", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.realm");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=SecretRotationServiceTest`
Expected: FAIL — compile errors because `SecretRotationService` still references the old model.

- [ ] **Step 3: Rewrite SecretRotationService.java**

```java
package com.k2iot.turncred.secret;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class SecretRotationService {

    private final TurnSecretRepository secretRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretRotationService(TurnSecretRepository secretRepository) {
        this.secretRepository = secretRepository;
    }

    @Transactional
    public String createInitialSecret(String realm) {
        String value = generateSecret();
        TurnSecret secret = new TurnSecret(new TurnSecretId(realm, value));
        secretRepository.save(secret);
        return value;
    }

    @Transactional
    public void rotate(String realm, Duration graceWindow) {
        // 1. Remove any rows that have already expired for this realm
        secretRepository.deleteExpiredForRealm(realm);

        // 2. Mark the current secret as expiring
        TurnSecret current = secretRepository.findCurrentByRealm(realm)
                .orElseThrow(() -> new IllegalStateException("No current secret for realm " + realm));
        current.setValidUntil(Instant.now().plus(graceWindow));
        secretRepository.save(current);

        // 3. Insert new current secret (valid_until = NULL)
        TurnSecret next = new TurnSecret(new TurnSecretId(realm, generateSecret()));
        secretRepository.save(next);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=SecretRotationServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/secret/SecretRotationService.java
git add src/test/java/com/k2iot/turncred/secret/SecretRotationServiceTest.java
git commit -m "feat(secret): rewrite SecretRotationService with transactional rotate() and cleanup"
```

---

### Task 5: Wire-up — update callers and integration tests

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java`
- Modify: `src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java`
- Modify: `src/test/java/com/k2iot/turncred/integration/CredentialIssuanceIntegrationTest.java`

**Interfaces:**
- Consumes: `TurnSecretRepository.findCurrentByRealm` (Task 3).
- Produces: `TurnCredentialService.issueCredential` signs with the `valid_until IS NULL` row. All tests green.

- [ ] **Step 1: Update TurnCredentialService.java (one line change)**

Replace line:
```java
TurnSecret secret = secretRepository.findByRealm(tenant.getRealm())
        .orElseThrow(() -> new IllegalStateException("No TURN secret configured for realm " + tenant.getRealm()));
```

With:
```java
TurnSecret secret = secretRepository.findCurrentByRealm(tenant.getRealm())
        .orElseThrow(() -> new IllegalStateException("No TURN secret configured for realm " + tenant.getRealm()));
```

- [ ] **Step 2: Update TurnCredentialServiceTest.java — swap mock stub**

Replace:
```java
when(secretRepository.findByRealm(tenant.getRealm())).thenReturn(Optional.of(secret));
```
With:
```java
when(secretRepository.findCurrentByRealm(tenant.getRealm())).thenReturn(Optional.of(secret));
```

And for the "no secret configured" test, replace:
```java
when(secretRepository.findByRealm(tenant.getRealm())).thenReturn(Optional.empty());
```
With:
```java
when(secretRepository.findCurrentByRealm(tenant.getRealm())).thenReturn(Optional.empty());
```

- [ ] **Step 3: Run unit tests — verify they pass**

Run: `./mvnw test -Dtest=TurnCredentialServiceTest`
Expected: PASS

- [ ] **Step 4: Update CredentialIssuanceIntegrationTest.java — add rotation assertions**

Add the following test method to the existing `CredentialIssuanceIntegrationTest` class:

```java
@Test
void afterRotationTwoRowsExistAndIssuanceStillWorks() throws Exception {
    // Create tenant
    var createBody = new java.util.HashMap<String, String>();
    createBody.put("name", "Rotation Test Corp");
    createBody.put("realm", "rot.turn.yourplatform.com");

    HttpHeaders jsonHeaders = new HttpHeaders();
    jsonHeaders.set("Content-Type", "application/json");
    jsonHeaders.set("X-Admin-Api-Key", "dev-admin-key");

    var createResponse = restTemplate.postForEntity("/v1/admin/tenants",
            new HttpEntity<>(objectMapper.writeValueAsString(createBody), jsonHeaders), String.class);
    assertThat(createResponse.getStatusCode().value()).isEqualTo(201);

    JsonNode created = objectMapper.readTree(createResponse.getBody());
    String tenantId = created.get("tenantId").asText();
    String apiKey = created.get("apiKey").asText();

    // Rotate secret
    var rotateResponse = restTemplate.exchange(
            "/v1/admin/tenants/" + tenantId + "/rotate-secret",
            HttpMethod.POST, new HttpEntity<>(jsonHeaders), Void.class);
    assertThat(rotateResponse.getStatusCode().value()).isEqualTo(204);

    // Credential issuance still works after rotation
    HttpHeaders authHeaders = new HttpHeaders();
    authHeaders.set("X-Api-Key", apiKey);
    authHeaders.set("Content-Type", "application/json");

    var credResponse = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
            new HttpEntity<>(authHeaders), String.class);
    assertThat(credResponse.getStatusCode().value()).isEqualTo(200);

    JsonNode credential = objectMapper.readTree(credResponse.getBody());
    assertThat(credential.get("username").asText()).contains(":");
    assertThat(credential.get("password").asText()).isNotBlank();
}
```

- [ ] **Step 5: Run all tests**

Run: `./mvnw test`
Expected: ALL PASS — zero failures across all test classes.

Output should include lines like:
```
Tests run: N, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java
git add src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java
git add src/test/java/com/k2iot/turncred/integration/CredentialIssuanceIntegrationTest.java
git commit -m "feat(secret): wire findCurrentByRealm into TurnCredentialService; update tests"
```

---

## Self-Review Checklist

Spec coverage check:

| Requirement | Task |
|---|---|
| Composite PK (realm, value) | Task 1 (V5 SQL), Task 2 (TurnSecretId) |
| valid_until IS NULL = current | Task 2 (entity), Task 3 (query), Task 4 (service) |
| Partial unique index uq_turn_secret_current | Task 1 |
| Atomic rotation transaction | Task 4 (@Transactional) |
| Expired cleanup on rotate | Task 4 (deleteExpiredForRealm) |
| Coturn-compatible: no app-specific columns | Task 1 (migration drops extra columns) |
| FK to tenants(realm) preserved | Task 1 (ON DELETE CASCADE) |
| Data migration: current secrets preserved | Task 1 (INSERT INTO ... SELECT) |
| Data migration: still-valid previous secrets preserved | Task 1 (conditional INSERT) |
| findByRealm removed | Task 3 |
| TurnCredentialService uses findCurrentByRealm | Task 5 |
| All tests pass | Task 5 |
