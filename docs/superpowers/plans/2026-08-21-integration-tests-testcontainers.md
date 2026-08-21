# Comprehensive Testcontainers Integration Test Suite

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a modular, complete integration test suite using Testcontainers (PostgreSQL 16 + Redis 7) covering all implemented features of the TURN Credential Platform (Admin Tenant Provisioning, Secret Rotation, TURN Ephemeral Credential Issuance, Rate Limiting, Audit Logging, and Operational Actuator Health/Metrics).

**Architecture:** Create an `AbstractIntegrationTest` base class that spins up shared PostgreSQL and Redis containers with Spring `@DynamicPropertySource`. Implement focused test classes extending this base class for each domain area to eliminate duplicate boilerplate and speed up test execution.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Testcontainers 1.20.1 (PostgreSQL & GenericContainer for Redis), Spring TestRestTemplate, AssertJ, Flyway.

## Global Constraints

- Testcontainers PostgreSQL image: `postgres:16` (database: `turncred`, user: `turncred`, pass: `turncred`).
- Testcontainers Redis image: `redis:7` (exposed port: 6379).
- Dynamic properties must map `spring.datasource.*` and `spring.data.redis.*`.
- All integration tests must live under package `com.k2iot.turncred.integration`.

---

### Task 1: Create `AbstractIntegrationTest` Base Class

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/AbstractIntegrationTest.java`
- Modify: `src/test/java/com/k2iot/turncred/integration/CredentialIssuanceIntegrationTest.java`

**Interfaces:**
- Consumes: Testcontainers `PostgreSQLContainer` and `GenericContainer`
- Produces: Base integration setup with initialized Spring Boot application context, shared DB/Redis container instances, `TestRestTemplate`, and `ObjectMapper`.

- [ ] **Step 1: Write `AbstractIntegrationTest` base class**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("turncred")
            .withUsername("turncred")
            .withPassword("turncred");

    @Container
    protected static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    protected final ObjectMapper objectMapper = new ObjectMapper();
}
```

- [ ] **Step 2: Refactor `CredentialIssuanceIntegrationTest` to inherit `AbstractIntegrationTest`**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialIssuanceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void issuedCredentialSignatureMatchesTenantSecretStoredInPostgres() throws Exception {
        var createBody = new java.util.HashMap<String, String>();
        createBody.put("name", "Acme Corp");
        createBody.put("realm", "acme.turn.yourplatform.com");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.set("Content-Type", "application/json");
        jsonHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createResponse = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), jsonHeaders), String.class);
        assertThat(createResponse.getStatusCode().value()).isEqualTo(201);

        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String apiKey = created.get("apiKey").asText();

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set("X-Api-Key", apiKey);
        authHeaders.set("Content-Type", "application/json");

        var credResponse = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(authHeaders), String.class);

        assertThat(credResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode credential = objectMapper.readTree(credResponse.getBody());
        assertThat(credential.get("username").asText()).contains(":");
        assertThat(credential.get("password").asText()).isNotBlank();
        assertThat(credential.get("ttlSeconds").asInt()).isEqualTo(3600);
    }
}
```

- [ ] **Step 3: Run maven test to verify base class refactoring**

Run: `./mvnw test -Dtest=CredentialIssuanceIntegrationTest`
Expected: BUILD SUCCESS (100% tests passing)

---

### Task 2: Tenant Admin Management Integration Tests

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/TenantAdminIntegrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`, `TenantRepository`, `TurnSecretRepository`
- Tests: `POST /v1/admin/tenants` (success, missing admin key 401, validation error 400, duplicate realm 409)

- [ ] **Step 1: Write failing `TenantAdminIntegrationTest`**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TurnSecretRepository secretRepository;

    @Test
    void createTenant_Success_PersistsTenantAndInitialSecret() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Beta Corp");
        body.put("realm", "beta.turn.yourplatform.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "dev-admin-key");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("tenantId")).isTrue();
        assertThat(json.get("realm").asText()).isEqualTo("beta.turn.yourplatform.com");
        assertThat(json.get("apiKey").asText()).startsWith("tcp_");

        UUID tenantId = UUID.fromString(json.get("tenantId").asText());
        assertThat(tenantRepository.findById(tenantId)).isPresent();
        assertThat(secretRepository.findCurrentByRealm("beta.turn.yourplatform.com")).isPresent();
    }

    @Test
    void createTenant_Unauthorized_WhenAdminApiKeyMissingOrInvalid() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Unauthorized Corp");
        body.put("realm", "unauth.turn.yourplatform.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "wrong-admin-key");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createTenant_BadRequest_WhenFieldsAreBlank() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "");
        body.put("realm", " ");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "dev-admin-key");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTenant_Conflict_WhenRealmAlreadyExists() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Duplicate Corp 1");
        body.put("realm", "duplicate.turn.yourplatform.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "dev-admin-key");

        restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        var duplicateBody = new HashMap<String, String>();
        duplicateBody.put("name", "Duplicate Corp 2");
        duplicateBody.put("realm", "duplicate.turn.yourplatform.com");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(duplicateBody), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

- [ ] **Step 2: Run maven test to verify `TenantAdminIntegrationTest` passes**

Run: `./mvnw test -Dtest=TenantAdminIntegrationTest`
Expected: PASS (4 tests passed)

---

### Task 3: Secret Rotation & Grace Period Integration Tests

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/SecretRotationIntegrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`, `TurnSecretRepository`
- Tests: `POST /v1/admin/tenants/{id}/rotate-secret` (success grace period DB state, 404 tenant not found, 401 unauthorized)

- [ ] **Step 1: Write `SecretRotationIntegrationTest`**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRotationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TurnSecretRepository secretRepository;

    @Test
    void rotateSecret_Success_TransitionsOldSecretToGracePeriodAndCreatesNewCurrentSecret() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Rotation Corp");
        body.put("realm", "rotation.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders), String.class);
        JsonNode json = objectMapper.readTree(createRes.getBody());
        String tenantId = json.get("tenantId").asText();

        TurnSecret initialSecret = secretRepository.findCurrentByRealm("rotation.turn.yourplatform.com").orElseThrow();

        var rotateRes = restTemplate.exchange("/v1/admin/tenants/" + tenantId + "/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(adminHeaders), Void.class);
        assertThat(rotateRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<TurnSecret> activeSecrets = secretRepository.findAllActiveByRealm("rotation.turn.yourplatform.com");
        assertThat(activeSecrets).hasSize(2);

        TurnSecret newCurrentSecret = secretRepository.findCurrentByRealm("rotation.turn.yourplatform.com").orElseThrow();
        assertThat(newCurrentSecret.getId().getValue()).isNotEqualTo(initialSecret.getId().getValue());
        assertThat(newCurrentSecret.getValidUntil()).isNull();
    }

    @Test
    void rotateSecret_NotFound_WhenTenantIdDoesNotExist() {
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        UUID nonExistentId = UUID.randomUUID();
        var rotateRes = restTemplate.exchange("/v1/admin/tenants/" + nonExistentId + "/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(adminHeaders), Void.class);

        assertThat(rotateRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run maven test to verify `SecretRotationIntegrationTest` passes**

Run: `./mvnw test -Dtest=SecretRotationIntegrationTest`
Expected: PASS (2 tests passed)

---

### Task 4: TURN Ephemeral Credential Issuance & Audit Logging Integration Tests

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/CredentialIssuanceExtendedIntegrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`, `CredentialIssuanceLogRepository`, `TenantRepository`
- Tests: `POST /v1/turn-credentials` (custom userId, missing/invalid API key 401, suspended tenant 401, audit log persistence)

- [ ] **Step 1: Write `CredentialIssuanceExtendedIntegrationTest`**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.credential.CredentialIssuanceLog;
import com.k2iot.turncred.credential.CredentialIssuanceLogRepository;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialIssuanceExtendedIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private CredentialIssuanceLogRepository logRepository;

    @Test
    void issueCredential_WithExplicitUserId_ReturnsFormattedUsernameAndSavesAuditLog() throws Exception {
        var createBody = new HashMap<String, String>();
        createBody.put("name", "Audit Corp");
        createBody.put("realm", "audit.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), adminHeaders), String.class);
        JsonNode tenantJson = objectMapper.readTree(createRes.getBody());
        String apiKey = tenantJson.get("apiKey").asText();
        UUID tenantId = UUID.fromString(tenantJson.get("tenantId").asText());

        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set("X-Api-Key", apiKey);
        clientHeaders.set("Content-Type", "application/json");

        var requestBody = new HashMap<String, String>();
        requestBody.put("userId", "custom-user-999");

        var response = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(requestBody), clientHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode credJson = objectMapper.readTree(response.getBody());
        assertThat(credJson.get("username").asText()).endsWith(":custom-user-999");

        List<CredentialIssuanceLog> logs = logRepository.findAll();
        assertThat(logs).anyMatch(l -> l.getTenantId().equals(tenantId) && "custom-user-999".equals(l.getUserId()));
    }

    @Test
    void issueCredential_Unauthorized_WhenApiKeyInvalidOrMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", "invalid-api-key");

        var response = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueCredential_Unauthorized_WhenTenantIsSuspended() throws Exception {
        var createBody = new HashMap<String, String>();
        createBody.put("name", "Suspended Corp");
        createBody.put("realm", "suspended.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), adminHeaders), String.class);
        JsonNode tenantJson = objectMapper.readTree(createRes.getBody());
        String apiKey = tenantJson.get("apiKey").asText();
        UUID tenantId = UUID.fromString(tenantJson.get("tenantId").asText());

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);

        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set("X-Api-Key", apiKey);

        var response = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(clientHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run maven test to verify `CredentialIssuanceExtendedIntegrationTest` passes**

Run: `./mvnw test -Dtest=CredentialIssuanceExtendedIntegrationTest`
Expected: PASS (3 tests passed)

---

### Task 5: Redis Rate Limiting & Actuator Health Integration Tests

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/RateLimitAndHealthIntegrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`, `TenantRepository`
- Tests: Redis rate limiting enforcement (429 Too Many Requests) and `/actuator/health` endpoint UP status.

- [ ] **Step 1: Write `RateLimitAndHealthIntegrationTest`**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitAndHealthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void issueCredential_RateLimitExceeded_Returns429TooManyRequests() throws Exception {
        var createBody = new HashMap<String, String>();
        createBody.put("name", "Limited Corp");
        createBody.put("realm", "limited.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), adminHeaders), String.class);
        JsonNode tenantJson = objectMapper.readTree(createRes.getBody());
        String apiKey = tenantJson.get("apiKey").asText();
        UUID tenantId = UUID.fromString(tenantJson.get("tenantId").asText());

        // Set low rate limit threshold (e.g. 2 requests per minute)
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setRateLimitPerMin(2);
        tenantRepository.save(tenant);

        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set("X-Api-Key", apiKey);

        // Request 1: OK
        var res1 = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST, new HttpEntity<>(clientHeaders), String.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Request 2: OK
        var res2 = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST, new HttpEntity<>(clientHeaders), String.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Request 3: Exceeded -> 429 TOO_MANY_REQUESTS
        var res3 = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST, new HttpEntity<>(clientHeaders), String.class);
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void actuatorHealth_ReturnsStatusUpWithPostgresAndRedisConnected() throws Exception {
        var response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.get("status").asText()).isEqualTo("UP");
    }
}
```

- [ ] **Step 2: Run maven test to verify `RateLimitAndHealthIntegrationTest` passes**

Run: `./mvnw test -Dtest=RateLimitAndHealthIntegrationTest`
Expected: PASS (2 tests passed)

---

### Task 6: Run Complete Integration Suite Verification

- [ ] **Step 1: Execute full test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS (All unit and integration tests passing)
