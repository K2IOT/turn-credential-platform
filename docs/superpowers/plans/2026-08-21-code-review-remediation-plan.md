# Code Review Remediation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all Critical and Important issues flagged in the code review of `feat/turn-credential-platform` (Coturn grace period secret query view, production HA database connection configuration, admin API key authentication, SHA-256 character encoding consolidation, MDC tenant logging timing, and missing unit test coverage).

**Architecture:** Add a Flyway migration database view `turn_secret_active` for Coturn grace-period secret queries, implement `AdminAuthInterceptor` for `/v1/admin/**` endpoints, consolidate SHA-256 hashing into `HashUtil`, fix request attribute propagation for MDC logging, align tenant rotation URL path to `{id}`, and add isolated unit tests.

**Tech Stack:** Java 21, Spring Boot 3.3, Flyway, PostgreSQL, Redis, JUnit 5, Testcontainers.

## Global Constraints

- Must maintain zero-downtime secret rotation grace period functionality for Coturn.
- Must preserve existing public API contracts (`POST /v1/turn-credentials`).
- All admin endpoints (`/v1/admin/**`) require `X-Admin-Api-Key` authentication.
- Must pass all existing and new unit and integration tests (`mvn test`).

---

### Task 1: Flyway migration for Coturn `turn_secret_active` View & Coturn DB Config

**Files:**
- Create: `src/main/resources/db/migration/V4__create_turn_secret_active_view.sql`
- Modify: `coturn/turnserver.conf`
- Modify: `docker-compose.prod.yml`

**Interfaces:**
- Produces: Database view `turn_secret_active` (`realm`, `value`) containing active and grace-period secrets for Coturn lookup.

- [ ] **Step 1: Write Flyway migration V4__create_turn_secret_active_view.sql**

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

- [ ] **Step 2: Update `coturn/turnserver.conf` to query `turn_secret_active` and use HAProxy host**

```ini
listening-port=3478
tls-listening-port=5349

realm=turn.yourplatform.com

use-auth-secret
psql-userdb="host=haproxy port=5000 dbname=turncred user=turncred password=turncred_password connect_timeout=10"
userdb-user-secret-query="SELECT value FROM turn_secret_active WHERE realm = 'turn.yourplatform.com'"

cert=/etc/coturn/turn_server.pem
pkey=/etc/coturn/turn_server.key
```

- [ ] **Step 3: Update `docker-compose.prod.yml` environment variables for coturn**

Pass `PSQL_HOST=${POSTGRES_HOST:-haproxy}` to coturn service.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V4__create_turn_secret_active_view.sql coturn/turnserver.conf docker-compose.prod.yml
git commit -m "feat: add turn_secret_active database view for coturn secret grace period"
```

---

### Task 2: Centralized `HashUtil` for SHA-256 and UTF-8 Character Encoding

**Files:**
- Create: `src/main/java/com/k2iot/turncred/util/HashUtil.java`
- Modify: `src/main/java/com/k2iot/turncred/admin/TenantAdminController.java`
- Modify: `src/main/java/com/k2iot/turncred/auth/TenantAuthInterceptor.java`
- Create: `src/test/java/com/k2iot/turncred/util/HashUtilTest.java`

**Interfaces:**
- Produces: `HashUtil.sha256Hex(String input)` returning deterministic hex string using `StandardCharsets.UTF_8`.

- [ ] **Step 1: Write unit test `HashUtilTest`**

```java
package com.k2iot.turncred.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HashUtilTest {

    @Test
    void sha256HexProducesCorrectHash() {
        String input = "test-api-key";
        String hash = HashUtil.sha256Hex(input);
        assertThat(hash).isEqualTo("532eaabd9574880dbf76b9b8cc00832c20a6ec113d682299550d7a6e0f345e25");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=HashUtilTest`
Expected: FAIL (cannot find symbol `HashUtil`).

- [ ] **Step 3: Implement `HashUtil`**

```java
package com.k2iot.turncred.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {

    public static String sha256Hex(String input) {
        if (input == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
```

- [ ] **Step 4: Update `TenantAdminController` and `TenantAuthInterceptor` to call `HashUtil.sha256Hex`**

- [ ] **Step 5: Run tests to verify pass**

Run: `mvn test -Dtest=HashUtilTest,TenantAuthInterceptorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/k2iot/turncred/util/HashUtil.java src/main/java/com/k2iot/turncred/admin/TenantAdminController.java src/main/java/com/k2iot/turncred/auth/TenantAuthInterceptor.java src/test/java/com/k2iot/turncred/util/HashUtilTest.java
git commit -m "refactor: centralize SHA-256 hashing with explicit UTF-8 encoding in HashUtil"
```

---

### Task 3: Admin API Key Authentication (`AdminAuthInterceptor`)

**Files:**
- Create: `src/main/java/com/k2iot/turncred/auth/AdminAuthInterceptor.java`
- Modify: `src/main/java/com/k2iot/turncred/config/WebConfig.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/k2iot/turncred/auth/AdminAuthInterceptorTest.java`

**Interfaces:**
- Consumes: `turn.admin.api-key` configuration property.
- Produces: Header-based authentication (`X-Admin-Api-Key`) interceptor for `/v1/admin/**`.

- [ ] **Step 1: Write `AdminAuthInterceptorTest`**

```java
package com.k2iot.turncred.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthInterceptorTest {

    private AdminAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AdminAuthInterceptor("dev-admin-key");
    }

    @Test
    void missingHeaderReturns401() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void validHeaderReturnsTrue() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Admin-Api-Key", "dev-admin-key");
        var res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result).isTrue();
    }
}
```

- [ ] **Step 2: Implement `AdminAuthInterceptor` and update `WebConfig`**

```java
package com.k2iot.turncred.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final String adminApiKey;

    public AdminAuthInterceptor(@Value("${turn.admin.api-key:dev-admin-key}") String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader("X-Admin-Api-Key");
        if (apiKey == null || !apiKey.equals(adminApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
```

Add `turn.admin.api-key: dev-admin-key` to `application.yml` and register `AdminAuthInterceptor` in `WebConfig.addInterceptors()` for `/v1/admin/**`.

- [ ] **Step 3: Run tests and commit**

Run: `mvn test -Dtest=AdminAuthInterceptorTest`
Expected: PASS.

```bash
git add src/main/java/com/k2iot/turncred/auth/AdminAuthInterceptor.java src/main/java/com/k2iot/turncred/config/WebConfig.java src/main/resources/application.yml src/test/java/com/k2iot/turncred/auth/AdminAuthInterceptorTest.java
git commit -m "feat: add AdminAuthInterceptor requiring X-Admin-Api-Key for admin endpoints"
```

---

### Task 4: Fix MDC Tenant Logging & Request Attribute Propagation

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/auth/TenantAuthInterceptor.java`
- Modify: `src/main/java/com/k2iot/turncred/logging/RequestLoggingFilter.java`

**Interfaces:**
- Produces: MDC `tenantId` populated accurately in access log filter.

- [ ] **Step 1: Set request attribute in `TenantAuthInterceptor`**

In `TenantAuthInterceptor.preHandle`:
`request.setAttribute("tenantId", tenant.getId().toString());`

- [ ] **Step 2: Update `RequestLoggingFilter` to check request attribute**

In `RequestLoggingFilter.doFilter` finally block:
`Object tenantIdAttr = httpRequest.getAttribute("tenantId");`
`String tenantIdStr = tenantIdAttr != null ? tenantIdAttr.toString() : (tenant != null ? tenant.getId().toString() : "anonymous");`

- [ ] **Step 3: Verify and commit**

Run: `mvn test`
Expected: PASS.

```bash
git add src/main/java/com/k2iot/turncred/auth/TenantAuthInterceptor.java src/main/java/com/k2iot/turncred/logging/RequestLoggingFilter.java
git commit -m "fix: propagate tenantId via request attribute for accurate MDC access logging"
```

---

### Task 5: Admin Secret Rotation Path Alignment & DTO Validation

**Files:**
- Modify: `src/main/java/com/k2iot/turncred/admin/TenantAdminController.java`
- Modify: `src/main/java/com/k2iot/turncred/admin/dto/CreateTenantRequest.java`
- Modify: `src/test/java/com/k2iot/turncred/admin/TenantAdminControllerTest.java`
- Modify: `src/test/java/com/k2iot/turncred/integration/CredentialIssuanceIntegrationTest.java`

- [ ] **Step 1: Update `TenantAdminController` to map `@PostMapping("/{id}/rotate-secret")`**

```java
@PostMapping("/{id}/rotate-secret")
public ResponseEntity<Void> rotateSecret(@PathVariable UUID id) {
    Tenant tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
    secretRotationService.rotateSecret(tenant.getRealm());
    return ResponseEntity.ok().build();
}
```

Add `@Valid` and `@NotBlank` to `CreateTenantRequest`.

- [ ] **Step 2: Update existing controller and integration tests to pass `X-Admin-Api-Key` and use tenant UUID in rotation endpoint path**

- [ ] **Step 3: Run all tests to verify**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/k2iot/turncred/admin/ src/test/java/com/k2iot/turncred/
git commit -m "fix: align secret rotation endpoint to use tenant UUID and add admin auth header to tests"
```

---

### Task 6: Unit Test Suite for `SecretRotationService`

**Files:**
- Create: `src/test/java/com/k2iot/turncred/secret/SecretRotationServiceTest.java`

- [ ] **Step 1: Write `SecretRotationServiceTest`**

```java
package com.k2iot.turncred.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    void createInitialSecretSavesNewTurnSecret() {
        String realm = "test.realm.com";
        when(secretRepository.findByRealm(realm)).thenReturn(Optional.empty());

        TurnSecret secret = rotationService.createInitialSecret(realm);

        assertThat(secret.getRealm()).isEqualTo(realm);
        assertThat(secret.getValue()).isNotBlank();
        verify(secretRepository).save(any(TurnSecret.class));
    }

    @Test
    void rotateSecretUpdatesPreviousValueAndGracePeriod() {
        String realm = "test.realm.com";
        TurnSecret existing = new TurnSecret(realm, "old-secret-value");
        when(secretRepository.findByRealm(realm)).thenReturn(Optional.of(existing));

        rotationService.rotateSecret(realm);

        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository).save(captor.capture());

        TurnSecret updated = captor.getValue();
        assertThat(updated.getPreviousValue()).isEqualTo("old-secret-value");
        assertThat(updated.getValue()).isNotEqualTo("old-secret-value");
        assertThat(updated.getPreviousValidUntil()).isAfter(Instant.now());
    }
}
```

- [ ] **Step 2: Run test suite**

Run: `mvn test -Dtest=SecretRotationServiceTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/k2iot/turncred/secret/SecretRotationServiceTest.java
git commit -m "test: add SecretRotationServiceTest verifying initial creation and rotation grace period mechanics"
```

---

## Verification Plan

### Automated Tests
- Run `mvn test` to execute all unit tests and integration tests.
