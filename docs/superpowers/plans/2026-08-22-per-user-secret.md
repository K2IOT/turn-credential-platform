# Per-UserId TURN Secret — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the platform so each pre-registered `userId` within a tenant has its own dedicated HMAC secret for TURN credential issuance, replacing the shared realm-level secret for those users.

**Architecture:** Add a nullable `user_id` column to `turn_secret` (NULL = realm-level, non-NULL = userId-scoped). Add a `tenant_user` registry table for pre-registration. `TurnCredentialService` validates the userId against `tenant_user` before signing with the userId's dedicated secret. The Coturn `psql-userdb` query is extended to also return userId-scoped secret rows.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Flyway, PostgreSQL 16, JUnit 5, Mockito, AssertJ, Testcontainers, Coturn psql-userdb.

## Global Constraints

- All new Java classes follow the existing package structure under `com.k2iot.turncred`
- Admin endpoints require `X-Admin-Api-Key: dev-admin-key` header (matched by `AdminAuthInterceptor`)
- Grace-period window is fixed at `Duration.ofMinutes(15)` (same as realm rotation)
- Run all tests with: `mvn test` from the project root
- Run a single test class with: `mvn test -Dtest=ClassName`
- Spec: `docs/superpowers/specs/2026-08-22-per-user-secret-design.md`

---

### Task 1: DB Migration — add `user_id` to `turn_secret` + create `tenant_user`

**Files:**
- Create: `src/main/resources/db/migration/V2__per_user_secret.sql`

**Interfaces:**
- Produces: `turn_secret.user_id` nullable column; `uq_turn_secret_current_realm` index; `uq_turn_secret_current_user` index; `tenant_user` table with `(id, tenant_id, user_id, status, created_at)`

---

- [ ] **Step 1: Create the migration file**

```sql
-- src/main/resources/db/migration/V2__per_user_secret.sql

-- 1. Add nullable user_id to turn_secret
ALTER TABLE turn_secret ADD COLUMN user_id VARCHAR(255) DEFAULT NULL;

-- 2. Drop the old "one active secret per realm" unique index
DROP INDEX uq_turn_secret_current;

-- 3. One active realm-level secret per realm (user_id IS NULL)
CREATE UNIQUE INDEX uq_turn_secret_current_realm
    ON turn_secret(realm)
    WHERE user_id IS NULL AND valid_until IS NULL;

-- 4. One active per-userId secret per (realm, user_id) pair
CREATE UNIQUE INDEX uq_turn_secret_current_user
    ON turn_secret(realm, user_id)
    WHERE user_id IS NOT NULL AND valid_until IS NULL;

-- 5. Index for efficient per-userId lookups
CREATE INDEX idx_turn_secret_user_id
    ON turn_secret(realm, user_id)
    WHERE user_id IS NOT NULL;

-- 6. New tenant_user registry
CREATE TABLE tenant_user (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id    VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_user UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_tenant_user_tenant ON tenant_user(tenant_id);
```

- [ ] **Step 2: Verify migration applies cleanly**

```bash
mvn test -Dtest=TurnSecretRepositoryTest
```
Expected: all existing tests PASS (migration ran, schema unchanged for existing queries).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V2__per_user_secret.sql
git commit -m "feat(db): add user_id to turn_secret and create tenant_user table (V2 migration)"
```

---

### Task 2: Extend `TurnSecretId`, `TurnSecret`, and `TurnSecretRepository` for per-userId secrets

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecretId.java`
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecret.java`
- Modify: `src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java`
- Modify: `src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java`

**Interfaces:**
- Consumes: V2 migration (Task 1)
- Produces:
  - `new TurnSecretId(String realm, String userId, String value)` — userId-scoped constructor
  - `TurnSecret.getUserId()` → `String` (nullable)
  - `TurnSecretRepository.findCurrentByRealmAndUserId(String realm, String userId)` → `Optional<TurnSecret>`
  - `TurnSecretRepository.deleteExpiredForRealmAndUserId(String realm, String userId)` → `void`

---

- [ ] **Step 1: Write failing repository tests**

Add these 3 tests inside the existing `TurnSecretRepositoryTest` class:

```java
@Test
void findCurrentByRealmAndUserId_returnsPerUserSecret() {
    TurnSecret userSecret = new TurnSecret(new TurnSecretId("realm.test", "alice", "user-secret-val"));
    secretRepository.save(userSecret);

    Optional<TurnSecret> found = secretRepository.findCurrentByRealmAndUserId("realm.test", "alice");

    assertThat(found).isPresent();
    assertThat(found.get().getValue()).isEqualTo("user-secret-val");
    assertThat(found.get().getUserId()).isEqualTo("alice");
}

@Test
void findCurrentByRealmAndUserId_doesNotReturnRealmLevelSecret() {
    TurnSecret realmSecret = new TurnSecret(new TurnSecretId("realm2.test", "realm-val"));
    secretRepository.save(realmSecret);

    Optional<TurnSecret> found = secretRepository.findCurrentByRealmAndUserId("realm2.test", "bob");

    assertThat(found).isEmpty();
}

@Test
void deleteExpiredForRealmAndUserId_removesOnlyThatUsersExpiredRows() {
    TurnSecret aliceExpired = new TurnSecret(new TurnSecretId("realm3.test", "alice", "alice-old"));
    aliceExpired.setValidUntil(Instant.now().minusSeconds(60));
    secretRepository.save(aliceExpired);

    TurnSecret bobExpired = new TurnSecret(new TurnSecretId("realm3.test", "bob", "bob-old"));
    bobExpired.setValidUntil(Instant.now().minusSeconds(60));
    secretRepository.save(bobExpired);

    secretRepository.deleteExpiredForRealmAndUserId("realm3.test", "alice");

    assertThat(secretRepository.findById(new TurnSecretId("realm3.test", "alice", "alice-old"))).isEmpty();
    assertThat(secretRepository.findById(new TurnSecretId("realm3.test", "bob", "bob-old"))).isPresent();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=TurnSecretRepositoryTest
```
Expected: FAIL — 3-arg `TurnSecretId` constructor, `getUserId()`, and new repo methods missing.

- [ ] **Step 3: Replace `TurnSecretId` with full updated version**

```java
// src/main/java/com/k2iot/turncred/secret/TurnSecretId.java
package com.k2iot.turncred.secret;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TurnSecretId implements Serializable {

    private String realm;
    private String value;
    private String userId; // nullable — null means realm-level secret

    public TurnSecretId() {}

    /** Realm-level secret (userId = null). */
    public TurnSecretId(String realm, String value) {
        this.realm = realm;
        this.value = value;
        this.userId = null;
    }

    /** Per-userId secret. */
    public TurnSecretId(String realm, String userId, String value) {
        this.realm = realm;
        this.userId = userId;
        this.value = value;
    }

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TurnSecretId that)) return false;
        return Objects.equals(realm, that.realm)
                && Objects.equals(value, that.value)
                && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, value, userId);
    }
}
```

- [ ] **Step 4: Replace `TurnSecret` with full updated version**

```java
// src/main/java/com/k2iot/turncred/secret/TurnSecret.java
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
    public String getUserId() { return id.getUserId(); } // null for realm-level secrets

    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Replace `TurnSecretRepository` with full updated version**

```java
// src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java
package com.k2iot.turncred.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TurnSecretRepository extends JpaRepository<TurnSecret, TurnSecretId> {

    // Realm-level queries (existing — now filter user_id IS NULL explicitly)

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm AND s.id.userId IS NULL AND s.validUntil IS NULL")
    Optional<TurnSecret> findCurrentByRealm(@Param("realm") String realm);

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm AND s.id.userId IS NULL " +
           "AND (s.validUntil IS NULL OR s.validUntil > CURRENT_TIMESTAMP)")
    List<TurnSecret> findValidByRealm(@Param("realm") String realm);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TurnSecret s WHERE s.id.realm = :realm AND s.id.userId IS NULL " +
           "AND s.validUntil IS NOT NULL AND s.validUntil <= CURRENT_TIMESTAMP")
    void deleteExpiredForRealm(@Param("realm") String realm);

    // Per-userId queries (new)

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm AND s.id.userId = :userId AND s.validUntil IS NULL")
    Optional<TurnSecret> findCurrentByRealmAndUserId(@Param("realm") String realm, @Param("userId") String userId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TurnSecret s WHERE s.id.realm = :realm AND s.id.userId = :userId " +
           "AND s.validUntil IS NOT NULL AND s.validUntil <= CURRENT_TIMESTAMP")
    void deleteExpiredForRealmAndUserId(@Param("realm") String realm, @Param("userId") String userId);
}
```

- [ ] **Step 6: Run repository tests**

```bash
mvn test -Dtest=TurnSecretRepositoryTest
```
Expected: all tests PASS (new + existing).

- [ ] **Step 7: Run full suite**

```bash
mvn test
```
Expected: all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/k2iot/turncred/secret/TurnSecretId.java \
        src/main/java/com/k2iot/turncred/secret/TurnSecret.java \
        src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java \
        src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java
git commit -m "feat(secret): extend TurnSecretId/TurnSecret/TurnSecretRepository for per-userId secrets"
```

---

### Task 3: `TenantUser` entity, `TenantUserStatus`, and `TenantUserRepository`

**Files:**
- Create: `src/main/java/com/k2iot/turncred/tenant/TenantUserStatus.java`
- Create: `src/main/java/com/k2iot/turncred/tenant/TenantUser.java`
- Create: `src/main/java/com/k2iot/turncred/tenant/TenantUserRepository.java`
- Create: `src/test/java/com/k2iot/turncred/tenant/TenantUserRepositoryTest.java`

**Interfaces:**
- Consumes: V2 migration (Task 1) — `tenant_user` table exists
- Produces:
  - `TenantUser` with `getId()` → `UUID`, `getTenantId()` → `UUID`, `getUserId()` → `String`, `getStatus()` → `TenantUserStatus`, `setStatus(TenantUserStatus)`
  - `TenantUserStatus` enum: `ACTIVE`, `SUSPENDED`
  - `TenantUserRepository.findByTenantIdAndUserId(UUID, String)` → `Optional<TenantUser>`

---

- [ ] **Step 1: Create `TenantUserStatus`**

```java
// src/main/java/com/k2iot/turncred/tenant/TenantUserStatus.java
package com.k2iot.turncred.tenant;

public enum TenantUserStatus {
    ACTIVE,
    SUSPENDED
}
```

- [ ] **Step 2: Create `TenantUser` entity**

```java
// src/main/java/com/k2iot/turncred/tenant/TenantUser.java
package com.k2iot.turncred.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_user")
public class TenantUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantUserStatus status = TenantUserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TenantUser() {}

    public TenantUser(UUID tenantId, String userId) {
        this.tenantId = tenantId;
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public TenantUserStatus getStatus() { return status; }
    public void setStatus(TenantUserStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: Create `TenantUserRepository`**

```java
// src/main/java/com/k2iot/turncred/tenant/TenantUserRepository.java
package com.k2iot.turncred.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantUserRepository extends JpaRepository<TenantUser, UUID> {

    Optional<TenantUser> findByTenantIdAndUserId(UUID tenantId, String userId);
}
```

- [ ] **Step 4: Write repository integration tests**

```java
// src/test/java/com/k2iot/turncred/tenant/TenantUserRepositoryTest.java
package com.k2iot.turncred.tenant;

import com.k2iot.turncred.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantUserRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private TenantUserRepository tenantUserRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private Tenant createTenant(String realm) {
        Tenant t = new Tenant();
        t.setName("Test Tenant");
        t.setRealm(realm);
        t.setApiKeyHash("hash-" + UUID.randomUUID());
        t.setStatus(TenantStatus.ACTIVE);
        t.setCredentialTtlSec(3600);
        t.setRateLimitPerMin(600);
        return tenantRepository.save(t);
    }

    @Test
    void findByTenantIdAndUserId_returnsActiveUser() {
        Tenant tenant = createTenant("repo-test-" + UUID.randomUUID() + ".com");
        tenantUserRepository.save(new TenantUser(tenant.getId(), "alice"));

        Optional<TenantUser> found = tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "alice");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo("alice");
        assertThat(found.get().getStatus()).isEqualTo(TenantUserStatus.ACTIVE);
    }

    @Test
    void findByTenantIdAndUserId_returnsEmptyWhenNotRegistered() {
        Tenant tenant = createTenant("repo-test2-" + UUID.randomUUID() + ".com");

        Optional<TenantUser> found = tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "unknown");

        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 5: Run tests**

```bash
mvn test -Dtest=TenantUserRepositoryTest
```
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/k2iot/turncred/tenant/TenantUserStatus.java \
        src/main/java/com/k2iot/turncred/tenant/TenantUser.java \
        src/main/java/com/k2iot/turncred/tenant/TenantUserRepository.java \
        src/test/java/com/k2iot/turncred/tenant/TenantUserRepositoryTest.java
git commit -m "feat(tenant): add TenantUser entity, TenantUserStatus, and TenantUserRepository"
```

---

### Task 4: `UserSecretRotationService`

**Files:**
- Create: `src/main/java/com/k2iot/turncred/secret/UserSecretRotationService.java`
- Create: `src/test/java/com/k2iot/turncred/secret/UserSecretRotationServiceTest.java`

**Interfaces:**
- Consumes:
  - `TenantUserRepository.findByTenantIdAndUserId(UUID, String)` → `Optional<TenantUser>` (Task 3)
  - `TurnSecretRepository.findCurrentByRealmAndUserId(String, String)` → `Optional<TurnSecret>` (Task 2)
  - `TurnSecretRepository.deleteExpiredForRealmAndUserId(String, String)` (Task 2)
  - `new TurnSecretId(String realm, String userId, String value)` (Task 2)
- Produces:
  - `UserSecretRotationService.registerUser(UUID tenantId, String realm, String userId)` → `void`
  - `UserSecretRotationService.rotateUserSecret(String realm, String userId, Duration graceWindow)` → `void`
  - `UserSecretRotationService.deregisterUser(UUID tenantId, String userId)` → `void`

---

- [ ] **Step 1: Write failing unit tests**

```java
// src/test/java/com/k2iot/turncred/secret/UserSecretRotationServiceTest.java
package com.k2iot.turncred.secret;

import com.k2iot.turncred.tenant.TenantUser;
import com.k2iot.turncred.tenant.TenantUserRepository;
import com.k2iot.turncred.tenant.TenantUserStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSecretRotationServiceTest {

    @Mock private TurnSecretRepository secretRepository;
    @Mock private TenantUserRepository tenantUserRepository;

    private UserSecretRotationService service;

    @BeforeEach
    void setUp() {
        service = new UserSecretRotationService(secretRepository, tenantUserRepository);
    }

    @Test
    void registerUser_createsTenantUserRowAndInitialSecret() {
        UUID tenantId = UUID.randomUUID();

        service.registerUser(tenantId, "acme.turn.com", "alice");

        ArgumentCaptor<TenantUser> userCaptor = ArgumentCaptor.forClass(TenantUser.class);
        verify(tenantUserRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(userCaptor.getValue().getUserId()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(TenantUserStatus.ACTIVE);

        ArgumentCaptor<TurnSecret> secretCaptor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository).save(secretCaptor.capture());
        assertThat(secretCaptor.getValue().getRealm()).isEqualTo("acme.turn.com");
        assertThat(secretCaptor.getValue().getUserId()).isEqualTo("alice");
        assertThat(secretCaptor.getValue().getValidUntil()).isNull();
        assertThat(secretCaptor.getValue().getValue()).isNotBlank();
    }

    @Test
    void rotateUserSecret_expiresCurrent_andInsertsNewCurrent() {
        TurnSecret current = new TurnSecret(new TurnSecretId("acme.turn.com", "alice", "old-secret"));
        when(secretRepository.findCurrentByRealmAndUserId("acme.turn.com", "alice"))
                .thenReturn(Optional.of(current));

        service.rotateUserSecret("acme.turn.com", "alice", Duration.ofMinutes(15));

        verify(secretRepository).deleteExpiredForRealmAndUserId("acme.turn.com", "alice");

        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository, times(2)).save(captor.capture());
        List<TurnSecret> saved = captor.getAllValues();

        assertThat(saved.get(0).getValue()).isEqualTo("old-secret");
        assertThat(saved.get(0).getValidUntil()).isAfter(Instant.now());
        assertThat(saved.get(1).getUserId()).isEqualTo("alice");
        assertThat(saved.get(1).getValidUntil()).isNull();
        assertThat(saved.get(1).getValue()).isNotEqualTo("old-secret");
    }

    @Test
    void rotateUserSecret_throwsWhenNoCurrentSecretExists() {
        when(secretRepository.findCurrentByRealmAndUserId("realm.com", "bob"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotateUserSecret("realm.com", "bob", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bob");
    }

    @Test
    void deregisterUser_setsTenantUserToSuspended() {
        UUID tenantId = UUID.randomUUID();
        TenantUser user = new TenantUser(tenantId, "alice");
        when(tenantUserRepository.findByTenantIdAndUserId(tenantId, "alice"))
                .thenReturn(Optional.of(user));

        service.deregisterUser(tenantId, "alice");

        assertThat(user.getStatus()).isEqualTo(TenantUserStatus.SUSPENDED);
        verify(tenantUserRepository).save(user);
    }

    @Test
    void deregisterUser_throwsWhenUserNotFound() {
        UUID tenantId = UUID.randomUUID();
        when(tenantUserRepository.findByTenantIdAndUserId(tenantId, "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deregisterUser(tenantId, "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=UserSecretRotationServiceTest
```
Expected: FAIL — `UserSecretRotationService` not found.

- [ ] **Step 3: Implement `UserSecretRotationService`**

```java
// src/main/java/com/k2iot/turncred/secret/UserSecretRotationService.java
package com.k2iot.turncred.secret;

import com.k2iot.turncred.tenant.TenantUser;
import com.k2iot.turncred.tenant.TenantUserRepository;
import com.k2iot.turncred.tenant.TenantUserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class UserSecretRotationService {

    private final TurnSecretRepository secretRepository;
    private final TenantUserRepository tenantUserRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserSecretRotationService(TurnSecretRepository secretRepository,
                                     TenantUserRepository tenantUserRepository) {
        this.secretRepository = secretRepository;
        this.tenantUserRepository = tenantUserRepository;
    }

    /** Register a userId: create tenant_user row + initial dedicated secret. */
    @Transactional
    public void registerUser(UUID tenantId, String realm, String userId) {
        TenantUser user = new TenantUser(tenantId, userId);
        tenantUserRepository.save(user);

        TurnSecret secret = new TurnSecret(new TurnSecretId(realm, userId, generateSecret()));
        secretRepository.save(secret);
    }

    /** Rotate a userId's secret with grace period (same pattern as realm rotation). */
    @Transactional
    public void rotateUserSecret(String realm, String userId, Duration graceWindow) {
        secretRepository.deleteExpiredForRealmAndUserId(realm, userId);

        TurnSecret current = secretRepository.findCurrentByRealmAndUserId(realm, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No current secret for user " + userId + " in realm " + realm));
        current.setValidUntil(Instant.now().plus(graceWindow));
        secretRepository.save(current);
        secretRepository.flush();

        TurnSecret next = new TurnSecret(new TurnSecretId(realm, userId, generateSecret()));
        secretRepository.save(next);
    }

    /** Suspend a userId — credential issuance will be rejected with 403. */
    @Transactional
    public void deregisterUser(UUID tenantId, String userId) {
        TenantUser user = tenantUserRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setStatus(TenantUserStatus.SUSPENDED);
        tenantUserRepository.save(user);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -Dtest=UserSecretRotationServiceTest
```
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/secret/UserSecretRotationService.java \
        src/test/java/com/k2iot/turncred/secret/UserSecretRotationServiceTest.java
git commit -m "feat(secret): add UserSecretRotationService for per-userId secret lifecycle"
```

---

### Task 5: Update `TurnCredentialService` — userId gate + sign with userId secret

**Files:**
- Create: `src/main/java/com/k2iot/turncred/credential/UserNotRegisteredException.java`
- Modify: `src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java`
- Modify: `src/main/java/com/k2iot/turncred/credential/CredentialExceptionHandler.java`
- Modify: `src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java`

**Interfaces:**
- Consumes:
  - `TenantUserRepository.findByTenantIdAndUserId(UUID, String)` → `Optional<TenantUser>` (Task 3)
  - `TurnSecretRepository.findCurrentByRealmAndUserId(String, String)` → `Optional<TurnSecret>` (Task 2)
- Produces: `TurnCredentialService.issueCredential(Tenant, String)` → `TurnCredential` (unchanged signature, new behavior)

---

- [ ] **Step 1: Create `UserNotRegisteredException`**

```java
// src/main/java/com/k2iot/turncred/credential/UserNotRegisteredException.java
package com.k2iot.turncred.credential;

public class UserNotRegisteredException extends RuntimeException {
    public UserNotRegisteredException(String userId) {
        super("User not registered or suspended: " + userId);
    }
}
```

- [ ] **Step 2: Replace `TurnCredentialService` with full updated version**

```java
// src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java
package com.k2iot.turncred.credential;

import com.k2iot.turncred.ratelimit.RedisRateLimiter;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantUser;
import com.k2iot.turncred.tenant.TenantUserRepository;
import com.k2iot.turncred.tenant.TenantUserStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TurnCredentialService {

    private final TurnSecretRepository secretRepository;
    private final RedisRateLimiter rateLimiter;
    private final CredentialIssuanceLogRepository logRepository;
    private final HmacSigner signer;
    private final TenantUserRepository tenantUserRepository;

    public TurnCredentialService(TurnSecretRepository secretRepository,
                                  RedisRateLimiter rateLimiter,
                                  CredentialIssuanceLogRepository logRepository,
                                  HmacSigner signer,
                                  TenantUserRepository tenantUserRepository) {
        this.secretRepository = secretRepository;
        this.rateLimiter = rateLimiter;
        this.logRepository = logRepository;
        this.signer = signer;
        this.tenantUserRepository = tenantUserRepository;
    }

    public TurnCredential issueCredential(Tenant tenant, String userId) {
        if (!rateLimiter.tryAcquire(tenant.getId(), tenant.getRateLimitPerMin())) {
            throw new RateLimitExceededException(tenant.getId());
        }

        TenantUser tenantUser = tenantUserRepository
                .findByTenantIdAndUserId(tenant.getId(), userId)
                .orElseThrow(() -> new UserNotRegisteredException(userId));
        if (tenantUser.getStatus() != TenantUserStatus.ACTIVE) {
            throw new UserNotRegisteredException(userId);
        }

        TurnSecret secret = secretRepository.findCurrentByRealmAndUserId(tenant.getRealm(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No TURN secret configured for user " + userId));

        long expiry = Instant.now().plusSeconds(tenant.getCredentialTtlSec()).getEpochSecond();
        String username = expiry + ":" + userId;
        String password = signer.sign(secret.getValue(), username);

        logRepository.save(new CredentialIssuanceLog(tenant.getId(), userId, tenant.getCredentialTtlSec()));

        List<String> uris = List.of(
                "turn:" + tenant.getRealm() + ":3478?transport=udp",
                "turns:" + tenant.getRealm() + ":5349?transport=tcp"
        );

        return new TurnCredential(username, password, tenant.getCredentialTtlSec(), uris);
    }
}
```

- [ ] **Step 3: Add `UserNotRegisteredException` handler to `CredentialExceptionHandler`**

```java
// src/main/java/com/k2iot/turncred/credential/CredentialExceptionHandler.java
package com.k2iot.turncred.credential;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CredentialExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public void handleRateLimit() {}

    @ExceptionHandler(UserNotRegisteredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void handleUserNotRegistered() {}

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void handleDuplicateKey() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNotFound() {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void handleValidation() {}

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public void handleMisconfiguredTenant() {}
}
```

- [ ] **Step 4: Replace `TurnCredentialServiceTest` with full updated version**

```java
// src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java
package com.k2iot.turncred.credential;

import com.k2iot.turncred.ratelimit.RedisRateLimiter;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretId;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantUser;
import com.k2iot.turncred.tenant.TenantUserRepository;
import com.k2iot.turncred.tenant.TenantUserStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TurnCredentialServiceTest {

    private final TurnSecretRepository secretRepository = mock(TurnSecretRepository.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final CredentialIssuanceLogRepository logRepository = mock(CredentialIssuanceLogRepository.class);
    private final HmacSigner signer = new HmacSigner();
    private final TenantUserRepository tenantUserRepository = mock(TenantUserRepository.class);

    private final TurnCredentialService service =
            new TurnCredentialService(secretRepository, rateLimiter, logRepository, signer, tenantUserRepository);

    private Tenant tenantWithRealm(String realm) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setRealm(realm);
        tenant.setCredentialTtlSec(3600);
        tenant.setRateLimitPerMin(600);
        return tenant;
    }

    @Test
    void issuesCredentialSignedWithUserSecret() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        TenantUser activeUser = new TenantUser(tenant.getId(), "user-42");
        TurnSecret secret = new TurnSecret(new TurnSecretId(tenant.getRealm(), "user-42", "super-secret"));

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "user-42"))
                .thenReturn(Optional.of(activeUser));
        when(secretRepository.findCurrentByRealmAndUserId(tenant.getRealm(), "user-42"))
                .thenReturn(Optional.of(secret));

        TurnCredential credential = service.issueCredential(tenant, "user-42");

        assertThat(credential.username()).endsWith(":user-42");
        assertThat(credential.password()).isEqualTo(new HmacSigner().sign("super-secret", credential.username()));
        assertThat(credential.ttlSeconds()).isEqualTo(3600);
        verify(logRepository).save(any(CredentialIssuanceLog.class));
        verify(secretRepository, never()).findCurrentByRealm(any());
    }

    @Test
    void throwsWhenRateLimitExceeded() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(false);

        assertThatThrownBy(() -> service.issueCredential(tenant, "user-42"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void throws403WhenUserNotRegistered() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCredential(tenant, "unknown"))
                .isInstanceOf(UserNotRegisteredException.class);
    }

    @Test
    void throws403WhenUserIsSuspended() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        TenantUser suspended = new TenantUser(tenant.getId(), "alice");
        suspended.setStatus(TenantUserStatus.SUSPENDED);

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "alice"))
                .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.issueCredential(tenant, "alice"))
                .isInstanceOf(UserNotRegisteredException.class);
    }

    @Test
    void throwsWhenNoSecretConfiguredForRegisteredUser() {
        Tenant tenant = tenantWithRealm("orphan.turn.yourplatform.com");
        TenantUser activeUser = new TenantUser(tenant.getId(), "alice");

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "alice"))
                .thenReturn(Optional.of(activeUser));
        when(secretRepository.findCurrentByRealmAndUserId(tenant.getRealm(), "alice"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCredential(tenant, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 5: Run unit tests**

```bash
mvn test -Dtest=TurnCredentialServiceTest
```
Expected: all 5 tests PASS.

- [ ] **Step 6: Run full suite**

```bash
mvn test
```
Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/k2iot/turncred/credential/UserNotRegisteredException.java \
        src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java \
        src/main/java/com/k2iot/turncred/credential/CredentialExceptionHandler.java \
        src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java
git commit -m "feat(credential): gate issuance on userId registration; sign with per-userId secret"
```

---

### Task 6: `TenantUserAdminController` — 3 new admin endpoints

**Files:**
- Create: `src/main/java/com/k2iot/turncred/admin/dto/RegisterUserRequest.java`
- Create: `src/main/java/com/k2iot/turncred/admin/dto/RegisterUserResponse.java`
- Create: `src/main/java/com/k2iot/turncred/admin/TenantUserAdminController.java`
- Create: `src/test/java/com/k2iot/turncred/admin/TenantUserAdminControllerTest.java`

**Interfaces:**
- Consumes:
  - `TenantRepository.findById(UUID)` → `Optional<Tenant>` (existing)
  - `UserSecretRotationService.registerUser(UUID, String, String)` (Task 4)
  - `UserSecretRotationService.rotateUserSecret(String, String, Duration)` (Task 4)
  - `UserSecretRotationService.deregisterUser(UUID, String)` (Task 4)
- Produces:
  - `POST /v1/admin/tenants/{tenantId}/users` → 201 `{ "userId": "...", "tenantId": "..." }`
  - `POST /v1/admin/tenants/{tenantId}/users/{userId}/rotate-secret` → 204
  - `DELETE /v1/admin/tenants/{tenantId}/users/{userId}` → 204

---

- [ ] **Step 1: Create DTOs**

```java
// src/main/java/com/k2iot/turncred/admin/dto/RegisterUserRequest.java
package com.k2iot.turncred.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterUserRequest(
        @NotBlank(message = "userId is required") String userId
) {}
```

```java
// src/main/java/com/k2iot/turncred/admin/dto/RegisterUserResponse.java
package com.k2iot.turncred.admin.dto;

public record RegisterUserResponse(String userId, String tenantId) {}
```

- [ ] **Step 2: Create `TenantUserAdminController`**

```java
// src/main/java/com/k2iot/turncred/admin/TenantUserAdminController.java
package com.k2iot.turncred.admin;

import com.k2iot.turncred.admin.dto.RegisterUserRequest;
import com.k2iot.turncred.admin.dto.RegisterUserResponse;
import com.k2iot.turncred.secret.UserSecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/users")
public class TenantUserAdminController {

    private final TenantRepository tenantRepository;
    private final UserSecretRotationService userSecretRotationService;

    public TenantUserAdminController(TenantRepository tenantRepository,
                                     UserSecretRotationService userSecretRotationService) {
        this.tenantRepository = tenantRepository;
        this.userSecretRotationService = userSecretRotationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterUserResponse register(@PathVariable UUID tenantId,
                                         @RequestBody @Valid RegisterUserRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        userSecretRotationService.registerUser(tenantId, tenant.getRealm(), request.userId());
        return new RegisterUserResponse(request.userId(), tenantId.toString());
    }

    @PostMapping("/{userId}/rotate-secret")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rotateUserSecret(@PathVariable UUID tenantId,
                                 @PathVariable String userId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        userSecretRotationService.rotateUserSecret(tenant.getRealm(), userId, Duration.ofMinutes(15));
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deregisterUser(@PathVariable UUID tenantId,
                                @PathVariable String userId) {
        userSecretRotationService.deregisterUser(tenantId, userId);
    }
}
```

- [ ] **Step 3: Write controller unit tests**

```java
// src/test/java/com/k2iot/turncred/admin/TenantUserAdminControllerTest.java
package com.k2iot.turncred.admin;

import com.k2iot.turncred.admin.dto.RegisterUserRequest;
import com.k2iot.turncred.secret.UserSecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TenantUserAdminControllerTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserSecretRotationService userSecretRotationService = mock(UserSecretRotationService.class);
    private final TenantUserAdminController controller =
            new TenantUserAdminController(tenantRepository, userSecretRotationService);

    private Tenant tenantWithRealm(UUID id, String realm) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setRealm(realm);
        return t;
    }

    @Test
    void register_callsRegisterUserWithCorrectArgs() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(tenantWithRealm(tenantId, "acme.turn.com")));

        controller.register(tenantId, new RegisterUserRequest("alice"));

        verify(userSecretRotationService).registerUser(tenantId, "acme.turn.com", "alice");
    }

    @Test
    void register_throwsNotFoundWhenTenantMissing() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.register(tenantId, new RegisterUserRequest("alice")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotateUserSecret_callsRotateWithGracePeriod() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(tenantWithRealm(tenantId, "acme.turn.com")));

        controller.rotateUserSecret(tenantId, "alice");

        verify(userSecretRotationService).rotateUserSecret("acme.turn.com", "alice", Duration.ofMinutes(15));
    }

    @Test
    void deregisterUser_callsDeregister() {
        UUID tenantId = UUID.randomUUID();

        controller.deregisterUser(tenantId, "alice");

        verify(userSecretRotationService).deregisterUser(tenantId, "alice");
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -Dtest=TenantUserAdminControllerTest
```
Expected: all 4 tests PASS.

- [ ] **Step 5: Run full suite**

```bash
mvn test
```
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/k2iot/turncred/admin/dto/RegisterUserRequest.java \
        src/main/java/com/k2iot/turncred/admin/dto/RegisterUserResponse.java \
        src/main/java/com/k2iot/turncred/admin/TenantUserAdminController.java \
        src/test/java/com/k2iot/turncred/admin/TenantUserAdminControllerTest.java
git commit -m "feat(admin): add TenantUserAdminController with register/rotate/deregister endpoints"
```

---

### Task 7: Integration tests + Coturn config update

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/PerUserSecretIntegrationTest.java`
- Modify: `coturn/turnserver.conf`

**Interfaces:**
- Consumes: Full feature stack from Tasks 1–6 wired end-to-end

---

- [ ] **Step 1: Write integration tests**

```java
// src/test/java/com/k2iot/turncred/integration/PerUserSecretIntegrationTest.java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.credential.HmacSigner;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerUserSecretIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TurnSecretRepository secretRepository;

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json");
        h.set("X-Admin-Api-Key", "dev-admin-key");
        return h;
    }

    private JsonNode createTenant(String realm) throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Test Tenant");
        body.put("realm", realm);
        var res = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(res.getBody());
    }

    private void registerUser(String tenantId, String userId) throws Exception {
        var body = new HashMap<String, String>();
        body.put("userId", userId);
        var res = restTemplate.postForEntity(
                "/v1/admin/tenants/" + tenantId + "/users",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void registerUser_returns201_andCreatesPerUserSecretRow() throws Exception {
        String realm = "per-user-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();

        registerUser(tenantId, "alice");

        var userSecret = secretRepository.findCurrentByRealmAndUserId(realm, "alice");
        assertThat(userSecret).isPresent();
        assertThat(userSecret.get().getUserId()).isEqualTo("alice");
        assertThat(userSecret.get().getValidUntil()).isNull();
    }

    @Test
    void registerUser_duplicate_returns409() throws Exception {
        String realm = "dup-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        registerUser(tenantId, "alice");

        var body = new HashMap<String, String>();
        body.put("userId", "alice");
        var res = restTemplate.postForEntity(
                "/v1/admin/tenants/" + tenantId + "/users",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void issueCredential_withRegisteredUser_returns200_signedWithUserSecret() throws Exception {
        String realm = "issue-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        String apiKey = tenant.get("apiKey").asText();
        registerUser(tenantId, "bob");

        TurnSecret userSecret = secretRepository.findCurrentByRealmAndUserId(realm, "bob").orElseThrow();

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "bob");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode cred = objectMapper.readTree(credRes.getBody());
        String expectedPassword = new HmacSigner().sign(userSecret.getValue(), cred.get("username").asText());
        assertThat(cred.get("password").asText()).isEqualTo(expectedPassword);
    }

    @Test
    void issueCredential_withUnregisteredUser_returns403() throws Exception {
        String realm = "unreg-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String apiKey = tenant.get("apiKey").asText();

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "not-registered");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void issueCredential_withSuspendedUser_returns403() throws Exception {
        String realm = "susp-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        String apiKey = tenant.get("apiKey").asText();
        registerUser(tenantId, "carol");

        var deleteRes = restTemplate.exchange(
                "/v1/admin/tenants/" + tenantId + "/users/carol",
                HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), Void.class);
        assertThat(deleteRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "carol");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rotateUserSecret_newIssuanceUsesNewSecret() throws Exception {
        String realm = "rotate-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        String apiKey = tenant.get("apiKey").asText();
        registerUser(tenantId, "dave");

        TurnSecret initialSecret = secretRepository.findCurrentByRealmAndUserId(realm, "dave").orElseThrow();

        var rotateRes = restTemplate.exchange(
                "/v1/admin/tenants/" + tenantId + "/users/dave/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(adminHeaders()), Void.class);
        assertThat(rotateRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        TurnSecret newSecret = secretRepository.findCurrentByRealmAndUserId(realm, "dave").orElseThrow();
        assertThat(newSecret.getValue()).isNotEqualTo(initialSecret.getValue());
        assertThat(newSecret.getValidUntil()).isNull();

        // Old secret is now in grace period
        TurnSecret graceSecret = secretRepository.findById(initialSecret.getId()).orElseThrow();
        assertThat(graceSecret.getValidUntil()).isNotNull();

        // New credential issuance uses the new secret
        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "dave");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode cred = objectMapper.readTree(credRes.getBody());
        String expectedPassword = new HmacSigner().sign(newSecret.getValue(), cred.get("username").asText());
        assertThat(cred.get("password").asText()).isEqualTo(expectedPassword);
    }
}
```

- [ ] **Step 2: Run integration tests**

```bash
mvn test -Dtest=PerUserSecretIntegrationTest
```
Expected: all 6 tests PASS.

- [ ] **Step 3: Run the full test suite**

```bash
mvn test
```
Expected: all tests PASS.

- [ ] **Step 4: Update Coturn `psql-userdb` query in `coturn/turnserver.conf`**

Replace the `userdb-user-secret-query` line with:

```
listening-port=3478
tls-listening-port=5349
listening-ip=0.0.0.0
relay-ip=0.0.0.0
external-ip=127.0.0.1
realm=e2e.turn.yourplatform.com

fingerprint
lt-cred-mech
use-auth-secret

# Per-userId and realm-level secret lookup.
# $1 = realm, $2 = username ("expiry:userId").
# substring extracts userId as everything after the first colon,
# correctly handling userIds that themselves contain colons.
psql-userdb="host=127.0.0.1 port=5432 dbname=turncred user=turncred password=turncred connect_timeout=10"
userdb-user-secret-query="SELECT value FROM turn_secret WHERE realm = $1 AND (user_id IS NULL OR user_id = substring($2 from position(':' in $2) + 1)) AND (valid_until IS NULL OR valid_until > NOW())"

no-cli
no-tlsv1
no-tlsv1_1

log-file=stdout
verbose
```

- [ ] **Step 5: Final commit**

```bash
git add src/test/java/com/k2iot/turncred/integration/PerUserSecretIntegrationTest.java \
        coturn/turnserver.conf
git commit -m "feat: per-userId TURN secret — integration tests + Coturn query update"
```

---

## Summary of all files changed

| Action | File |
|---|---|
| CREATE | `src/main/resources/db/migration/V2__per_user_secret.sql` |
| MODIFY | `src/main/java/com/k2iot/turncred/secret/TurnSecretId.java` |
| MODIFY | `src/main/java/com/k2iot/turncred/secret/TurnSecret.java` |
| MODIFY | `src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java` |
| CREATE | `src/main/java/com/k2iot/turncred/tenant/TenantUserStatus.java` |
| CREATE | `src/main/java/com/k2iot/turncred/tenant/TenantUser.java` |
| CREATE | `src/main/java/com/k2iot/turncred/tenant/TenantUserRepository.java` |
| CREATE | `src/main/java/com/k2iot/turncred/secret/UserSecretRotationService.java` |
| CREATE | `src/main/java/com/k2iot/turncred/credential/UserNotRegisteredException.java` |
| MODIFY | `src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java` |
| MODIFY | `src/main/java/com/k2iot/turncred/credential/CredentialExceptionHandler.java` |
| CREATE | `src/main/java/com/k2iot/turncred/admin/dto/RegisterUserRequest.java` |
| CREATE | `src/main/java/com/k2iot/turncred/admin/dto/RegisterUserResponse.java` |
| CREATE | `src/main/java/com/k2iot/turncred/admin/TenantUserAdminController.java` |
| MODIFY | `coturn/turnserver.conf` |
| MODIFY | `src/test/java/com/k2iot/turncred/secret/TurnSecretRepositoryTest.java` |
| CREATE | `src/test/java/com/k2iot/turncred/tenant/TenantUserRepositoryTest.java` |
| CREATE | `src/test/java/com/k2iot/turncred/secret/UserSecretRotationServiceTest.java` |
| MODIFY | `src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java` |
| CREATE | `src/test/java/com/k2iot/turncred/admin/TenantUserAdminControllerTest.java` |
| CREATE | `src/test/java/com/k2iot/turncred/integration/PerUserSecretIntegrationTest.java` |
