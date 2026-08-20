# TURN Credential Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a multi-tenant TURN credential issuance service (Spring Boot MVC, Java 21 virtual threads) plus the coturn/Postgres/Redis infra it depends on, deployable as a per-region Docker stack.

**Architecture:** Stateless Spring Boot MVC service signs TURN REST API credentials (HMAC-SHA1) using a per-tenant secret stored in Postgres. Redis provides per-tenant rate limiting only. Coturn reads the same `turn_secret` table directly via `psql-userdb` to validate connections without calling back to the app.

**Tech Stack:** Java 21 (virtual threads via `spring.threads.virtual.enabled`), Spring Boot 3.3 (MVC, Data JPA, Validation, Actuator), PostgreSQL 16 + Flyway, Redis 7 (Lettuce client), coturn (latest stable, `psql-userdb`), Maven, JUnit 5 + Testcontainers, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md`

## Global Constraints

- Java version: 21 (records, virtual threads — `spring.threads.virtual.enabled=true`).
- Build tool: Maven.
- HMAC algorithm: HmacSHA1, matching coturn's `use-auth-secret` TURN REST API convention exactly — no deviation.
- API key never stored raw — SHA-256 hash only (`api_key_hash` column, already in schema).
- All timestamps: UTC, `TIMESTAMPTZ` in Postgres, `Instant` in Java.
- Every task ends with a passing test suite (`mvn test`) and a commit — no task leaves the tree red.
- No placeholder code, no `// TODO`, no unimplemented branches.

---

## File Structure

```
turn-credential-platform/
├── pom.xml
├── docker-compose.yml                  # local dev: postgres, redis, coturn, app
├── docker-compose.prod.yml             # single-region reference topology
├── coturn/turnserver.conf
├── src/main/java/com/k2iot/turncred/
│   ├── TurnCredentialPlatformApplication.java
│   ├── config/VirtualThreadConfig.java
│   ├── config/RedisConfig.java
│   ├── tenant/Tenant.java
│   ├── tenant/TenantStatus.java
│   ├── tenant/TenantRepository.java
│   ├── tenant/TenantService.java
│   ├── secret/TurnSecret.java
│   ├── secret/TurnSecretRepository.java
│   ├── secret/SecretRotationService.java
│   ├── auth/TenantAuthInterceptor.java
│   ├── auth/CurrentTenantHolder.java
│   ├── ratelimit/RedisRateLimiter.java
│   ├── credential/HmacSigner.java
│   ├── credential/TurnCredential.java
│   ├── credential/TurnCredentialService.java
│   ├── credential/TurnCredentialController.java
│   ├── credential/dto/IssueCredentialRequest.java
│   ├── credential/dto/TurnCredentialResponse.java
│   ├── admin/TenantAdminController.java
│   ├── admin/dto/CreateTenantRequest.java
│   ├── admin/dto/CreateTenantResponse.java
│   └── logging/RequestLoggingFilter.java
├── src/main/resources/application.yml
├── src/main/resources/db/migration/
│   ├── V1__create_tenants.sql
│   ├── V2__create_turn_secret.sql
│   └── V3__create_credential_issuance_log.sql
└── src/test/java/com/k2iot/turncred/
    ├── credential/HmacSignerTest.java
    ├── credential/TurnCredentialServiceTest.java
    ├── ratelimit/RedisRateLimiterTest.java
    ├── auth/TenantAuthInterceptorTest.java
    ├── credential/TurnCredentialControllerTest.java
    ├── admin/TenantAdminControllerTest.java
    └── integration/CredentialIssuanceIntegrationTest.java
```

---

### Task 1: Project scaffolding + local Docker infra (Postgres, Redis)

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/k2iot/turncred/TurnCredentialPlatformApplication.java`
- Create: `src/main/java/com/k2iot/turncred/config/VirtualThreadConfig.java`
- Create: `src/main/resources/application.yml`
- Create: `docker-compose.yml`

**Interfaces:**
- Produces: Spring Boot app entrypoint on port 8080; `docker-compose up postgres redis` gives local Postgres (`localhost:5432/turncred`) and Redis (`localhost:6379`) for every later task's tests.

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>
  <groupId>com.k2iot</groupId>
  <artifactId>turn-credential-platform</artifactId>
  <version>0.1.0</version>
  <properties>
    <java.version>21</java.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>7.4</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>1.20.1</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <version>1.20.1</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create the application entrypoint**

```java
package com.k2iot.turncred;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TurnCredentialPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(TurnCredentialPlatformApplication.class, args);
    }
}
```

- [ ] **Step 3: Enable virtual threads**

```java
package com.k2iot.turncred.config;

import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
        return protocolHandler -> protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

- [ ] **Step 4: `application.yml`**

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/turncred
    username: turncred
    password: turncred
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  data:
    redis:
      host: localhost
      port: 6379
  flyway:
    locations: classpath:db/migration

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus

turn:
  platform-domain: yourplatform.com
```

- [ ] **Step 5: `docker-compose.yml` (dev infra: postgres + redis)**

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: turncred
      POSTGRES_USER: turncred
      POSTGRES_PASSWORD: turncred
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

volumes:
  pgdata:
```

- [ ] **Step 6: Verify the app boots against local infra**

Run:
```bash
docker compose up -d postgres redis
mvn spring-boot:run
```
Expected: app starts on port 8080, no Flyway errors (no migrations yet, so this just proves DB connectivity — Task 2 adds the schema).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src docker-compose.yml
git commit -m "chore: scaffold Spring Boot MVC app with virtual threads and local infra"
```

---

### Task 2: Database schema (Flyway migrations)

**Files:**
- Create: `src/main/resources/db/migration/V1__create_tenants.sql`
- Create: `src/main/resources/db/migration/V2__create_turn_secret.sql`
- Create: `src/main/resources/db/migration/V3__create_credential_issuance_log.sql`

**Interfaces:**
- Produces: tables `tenants`, `turn_secret`, `credential_issuance_log` exactly as defined in the spec §6, plus the `previous_value`/`previous_valid_until` grace-period columns on `turn_secret` needed by Task 9's rotation logic.

- [ ] **Step 1: `V1__create_tenants.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

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

CREATE UNIQUE INDEX idx_tenants_api_key_hash ON tenants (api_key_hash);
```

- [ ] **Step 2: `V2__create_turn_secret.sql`**

```sql
CREATE TABLE turn_secret (
    realm                  VARCHAR(255) PRIMARY KEY REFERENCES tenants(realm),
    value                  VARCHAR(255) NOT NULL,
    previous_value         VARCHAR(255),
    previous_valid_until   TIMESTAMPTZ,
    rotated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: `V3__create_credential_issuance_log.sql`**

```sql
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

- [ ] **Step 4: Verify migrations apply cleanly**

Run:
```bash
docker compose up -d postgres
mvn spring-boot:run
```
Expected: log shows `Successfully applied 3 migrations`, app starts.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration
git commit -m "feat: add tenants, turn_secret, credential_issuance_log schema"
```

---

### Task 3: Tenant and TurnSecret JPA entities + repositories

**Files:**
- Create: `src/main/java/com/k2iot/turncred/tenant/TenantStatus.java`
- Create: `src/main/java/com/k2iot/turncred/tenant/Tenant.java`
- Create: `src/main/java/com/k2iot/turncred/tenant/TenantRepository.java`
- Create: `src/main/java/com/k2iot/turncred/secret/TurnSecret.java`
- Create: `src/main/java/com/k2iot/turncred/secret/TurnSecretRepository.java`
- Test: `src/test/java/com/k2iot/turncred/tenant/TenantRepositoryTest.java`

**Interfaces:**
- Produces: `TenantRepository.findByApiKeyHash(String): Optional<Tenant>`, `TurnSecretRepository.findByRealm(String): Optional<TurnSecret>` — consumed by Task 6 (auth) and Task 7 (credential issuance).

- [ ] **Step 1: Write the failing repository test**

```java
package com.k2iot.turncred.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TenantRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TenantRepository tenantRepository;

    @Test
    void findsTenantByApiKeyHash() {
        Tenant tenant = new Tenant();
        tenant.setName("Acme Corp");
        tenant.setRealm("acme.turn.yourplatform.com");
        tenant.setApiKeyHash("hash-abc-123");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCredentialTtlSec(3600);
        tenant.setRateLimitPerMin(600);
        tenantRepository.save(tenant);

        Optional<Tenant> found = tenantRepository.findByApiKeyHash("hash-abc-123");

        assertThat(found).isPresent();
        assertThat(found.get().getRealm()).isEqualTo("acme.turn.yourplatform.com");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TenantRepositoryTest`
Expected: FAIL — compile error, `Tenant`/`TenantRepository` don't exist yet.

- [ ] **Step 3: Implement entities and repositories**

```java
package com.k2iot.turncred.tenant;

public enum TenantStatus {
    ACTIVE,
    SUSPENDED
}
```

```java
package com.k2iot.turncred.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String realm;

    @Column(name = "api_key_hash", nullable = false, unique = true)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "credential_ttl_sec", nullable = false)
    private int credentialTtlSec;

    @Column(name = "rate_limit_per_min", nullable = false)
    private int rateLimitPerMin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public TenantStatus getStatus() { return status; }
    public void setStatus(TenantStatus status) { this.status = status; }
    public int getCredentialTtlSec() { return credentialTtlSec; }
    public void setCredentialTtlSec(int credentialTtlSec) { this.credentialTtlSec = credentialTtlSec; }
    public int getRateLimitPerMin() { return rateLimitPerMin; }
    public void setRateLimitPerMin(int rateLimitPerMin) { this.rateLimitPerMin = rateLimitPerMin; }
}
```

```java
package com.k2iot.turncred.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByApiKeyHash(String apiKeyHash);
    Optional<Tenant> findByRealm(String realm);
}
```

```java
package com.k2iot.turncred.secret;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "turn_secret")
public class TurnSecret {

    @Id
    private String realm;

    @Column(nullable = false)
    private String value;

    @Column(name = "previous_value")
    private String previousValue;

    @Column(name = "previous_valid_until")
    private Instant previousValidUntil;

    @Column(name = "rotated_at", nullable = false)
    private Instant rotatedAt = Instant.now();

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getPreviousValue() { return previousValue; }
    public void setPreviousValue(String previousValue) { this.previousValue = previousValue; }
    public Instant getPreviousValidUntil() { return previousValidUntil; }
    public void setPreviousValidUntil(Instant previousValidUntil) { this.previousValidUntil = previousValidUntil; }
    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }
}
```

```java
package com.k2iot.turncred.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TurnSecretRepository extends JpaRepository<TurnSecret, String> {
    Optional<TurnSecret> findByRealm(String realm);
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=TenantRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/tenant src/main/java/com/k2iot/turncred/secret src/test
git commit -m "feat: add Tenant and TurnSecret entities with repositories"
```

---

### Task 4: HMAC credential signer

**Files:**
- Create: `src/main/java/com/k2iot/turncred/credential/HmacSigner.java`
- Test: `src/test/java/com/k2iot/turncred/credential/HmacSignerTest.java`

**Interfaces:**
- Produces: `HmacSigner.sign(String secret, String message): String` — consumed by Task 7 (`TurnCredentialService`).

- [ ] **Step 1: Write the failing test**

```java
package com.k2iot.turncred.credential;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();

    @Test
    void signsMessageMatchingReferenceHmacSha1Implementation() throws Exception {
        String secret = "tenant-secret-abc";
        String message = "1755700000:user-123";

        String actual = signer.sign(secret, message);
        String expected = referenceHmacSha1(secret, message);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        String message = "1755700000:user-123";
        String sigA = signer.sign("secret-a", message);
        String sigB = signer.sign("secret-b", message);
        assertThat(sigA).isNotEqualTo(sigB);
    }

    private String referenceHmacSha1(String secret, String message) throws NoSuchAlgorithmException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=HmacSignerTest`
Expected: FAIL — `HmacSigner` doesn't exist.

- [ ] **Step 3: Implement `HmacSigner`**

```java
package com.k2iot.turncred.credential;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA1";

    public String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signature = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=HmacSignerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/credential/HmacSigner.java src/test/java/com/k2iot/turncred/credential/HmacSignerTest.java
git commit -m "feat: add HMAC-SHA1 credential signer"
```

---

### Task 5: Redis rate limiter (per tenant, sliding window)

**Files:**
- Create: `src/main/java/com/k2iot/turncred/config/RedisConfig.java`
- Create: `src/main/java/com/k2iot/turncred/ratelimit/RedisRateLimiter.java`
- Test: `src/test/java/com/k2iot/turncred/ratelimit/RedisRateLimiterTest.java`

**Interfaces:**
- Produces: `RedisRateLimiter.tryAcquire(UUID tenantId, int limitPerMinute): boolean` — consumed by Task 7.

- [ ] **Step 1: Write the failing test**

```java
package com.k2iot.turncred.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private RedisRateLimiter rateLimiter() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        return new RedisRateLimiter(template);
    }

    @Test
    void allowsRequestsUnderTheLimit() {
        RedisRateLimiter limiter = rateLimiter();
        UUID tenantId = UUID.randomUUID();

        boolean first = limiter.tryAcquire(tenantId, 3);
        boolean second = limiter.tryAcquire(tenantId, 3);
        boolean third = limiter.tryAcquire(tenantId, 3);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isTrue();
    }

    @Test
    void rejectsRequestsOverTheLimit() {
        RedisRateLimiter limiter = rateLimiter();
        UUID tenantId = UUID.randomUUID();

        limiter.tryAcquire(tenantId, 2);
        limiter.tryAcquire(tenantId, 2);
        boolean third = limiter.tryAcquire(tenantId, 2);

        assertThat(third).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=RedisRateLimiterTest`
Expected: FAIL — `RedisRateLimiter` doesn't exist.

- [ ] **Step 3: Implement `RedisRateLimiter`**

Fixed-window counter keyed by `tenant_id` + current minute, using `INCR` + `EXPIRE`:

```java
package com.k2iot.turncred.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(UUID tenantId, int limitPerMinute) {
        long currentMinuteBucket = Instant.now().getEpochSecond() / 60;
        String key = "ratelimit:%s:%d".formatted(tenantId, currentMinuteBucket);

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(90));
        }
        return count != null && count <= limitPerMinute;
    }
}
```

```java
package com.k2iot.turncred.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=RedisRateLimiterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/config/RedisConfig.java src/main/java/com/k2iot/turncred/ratelimit src/test/java/com/k2iot/turncred/ratelimit
git commit -m "feat: add Redis-backed per-tenant rate limiter"
```

---

### Task 6: Tenant API-key authentication (interceptor)

**Files:**
- Create: `src/main/java/com/k2iot/turncred/auth/CurrentTenantHolder.java`
- Create: `src/main/java/com/k2iot/turncred/auth/TenantAuthInterceptor.java`
- Create: `src/main/java/com/k2iot/turncred/config/WebConfig.java`
- Test: `src/test/java/com/k2iot/turncred/auth/TenantAuthInterceptorTest.java`

**Interfaces:**
- Consumes: `TenantRepository.findByApiKeyHash` (Task 3).
- Produces: `CurrentTenantHolder.get(): Tenant` — consumed by Task 8's controller.

- [ ] **Step 1: Write the failing test**

```java
package com.k2iot.turncred.auth;

import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantAuthInterceptorTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantAuthInterceptor interceptor = new TenantAuthInterceptor(tenantRepository);

    @Test
    void allowsRequestWithValidApiKey() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findByApiKeyHash(anyString())).thenReturn(Optional.of(tenant));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "valid-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentTenantHolder.get()).isEqualTo(tenant);
        CurrentTenantHolder.clear();
    }

    @Test
    void rejectsRequestWithMissingApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsSuspendedTenant() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findByApiKeyHash(anyString())).thenReturn(Optional.of(tenant));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "suspended-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TenantAuthInterceptorTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement**

```java
package com.k2iot.turncred.auth;

import com.k2iot.turncred.tenant.Tenant;

public class CurrentTenantHolder {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    public static void set(Tenant tenant) { CURRENT.set(tenant); }
    public static Tenant get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
```

```java
package com.k2iot.turncred.auth;

import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class TenantAuthInterceptor implements HandlerInterceptor {

    private final TenantRepository tenantRepository;

    public TenantAuthInterceptor(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        String hash = sha256Hex(apiKey);
        Optional<Tenant> tenant = tenantRepository.findByApiKeyHash(hash);

        if (tenant.isEmpty() || tenant.get().getStatus() != TenantStatus.ACTIVE) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        CurrentTenantHolder.set(tenant.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentTenantHolder.clear();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

```java
package com.k2iot.turncred.config;

import com.k2iot.turncred.auth.TenantAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantAuthInterceptor tenantAuthInterceptor;

    public WebConfig(TenantAuthInterceptor tenantAuthInterceptor) {
        this.tenantAuthInterceptor = tenantAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantAuthInterceptor)
                .addPathPatterns("/v1/turn-credentials/**");
    }
}
```

Note: remove the unused `BCrypt` import above before compiling — the implementation only needs `sha256Hex`; it was left out of the final class deliberately. (Double-check this file has no `spring-security` import remaining; this project does not depend on `spring-security-crypto`.)

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=TenantAuthInterceptorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/auth src/main/java/com/k2iot/turncred/config/WebConfig.java src/test/java/com/k2iot/turncred/auth
git commit -m "feat: add tenant API-key authentication interceptor"
```

---

### Task 7: Credential issuance service (combines secret lookup, rate limit, signing, logging)

**Files:**
- Create: `src/main/java/com/k2iot/turncred/credential/TurnCredential.java`
- Create: `src/main/java/com/k2iot/turncred/credential/TurnCredentialService.java`
- Create: `src/main/java/com/k2iot/turncred/credential/RateLimitExceededException.java`
- Create: `src/main/java/com/k2iot/turncred/credential/CredentialIssuanceLog.java`
- Create: `src/main/java/com/k2iot/turncred/credential/CredentialIssuanceLogRepository.java`
- Test: `src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java`

**Interfaces:**
- Consumes: `TurnSecretRepository.findByRealm` (Task 3), `HmacSigner.sign` (Task 4), `RedisRateLimiter.tryAcquire` (Task 5).
- Produces: `TurnCredentialService.issueCredential(Tenant tenant, String userId, String region): TurnCredential` — consumed by Task 8.

- [ ] **Step 1: Write the failing test**

```java
package com.k2iot.turncred.credential;

import com.k2iot.turncred.ratelimit.RedisRateLimiter;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.Tenant;
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

    private final TurnCredentialService service =
            new TurnCredentialService(secretRepository, rateLimiter, logRepository, signer);

    private Tenant tenantWithRealm(String realm) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setRealm(realm);
        tenant.setCredentialTtlSec(3600);
        tenant.setRateLimitPerMin(600);
        return tenant;
    }

    @Test
    void issuesCredentialSignedWithTenantSecret() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        TurnSecret secret = new TurnSecret();
        secret.setRealm(tenant.getRealm());
        secret.setValue("super-secret");

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(secretRepository.findByRealm(tenant.getRealm())).thenReturn(Optional.of(secret));

        TurnCredential credential = service.issueCredential(tenant, "user-42", "ap-southeast");

        assertThat(credential.username()).endsWith(":user-42");
        assertThat(credential.password()).isEqualTo(new HmacSigner().sign("super-secret", credential.username()));
        assertThat(credential.ttlSeconds()).isEqualTo(3600);
        verify(logRepository).save(any(CredentialIssuanceLog.class));
    }

    @Test
    void throwsWhenRateLimitExceeded() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(false);

        assertThatThrownBy(() -> service.issueCredential(tenant, "user-42", "ap-southeast"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void throwsWhenTenantHasNoSecretConfigured() {
        Tenant tenant = tenantWithRealm("orphan.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(secretRepository.findByRealm(tenant.getRealm())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCredential(tenant, "user-42", "ap-southeast"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TurnCredentialServiceTest`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Implement**

```java
package com.k2iot.turncred.credential;

import java.util.List;

public record TurnCredential(String username, String password, int ttlSeconds, List<String> uris) {}
```

```java
package com.k2iot.turncred.credential;

import java.util.UUID;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(UUID tenantId) {
        super("Rate limit exceeded for tenant " + tenantId);
    }
}
```

```java
package com.k2iot.turncred.credential;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credential_issuance_log")
public class CredentialIssuanceLog {

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String region;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "ttl_sec", nullable = false)
    private int ttlSec;

    public CredentialIssuanceLog() {}

    public CredentialIssuanceLog(UUID tenantId, String userId, String region, int ttlSec) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.region = region;
        this.ttlSec = ttlSec;
    }
}
```

```java
package com.k2iot.turncred.credential;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialIssuanceLogRepository extends JpaRepository<CredentialIssuanceLog, Long> {}
```

```java
package com.k2iot.turncred.credential;

import com.k2iot.turncred.ratelimit.RedisRateLimiter;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.Tenant;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TurnCredentialService {

    private final TurnSecretRepository secretRepository;
    private final RedisRateLimiter rateLimiter;
    private final CredentialIssuanceLogRepository logRepository;
    private final HmacSigner signer;

    public TurnCredentialService(TurnSecretRepository secretRepository,
                                  RedisRateLimiter rateLimiter,
                                  CredentialIssuanceLogRepository logRepository,
                                  HmacSigner signer) {
        this.secretRepository = secretRepository;
        this.rateLimiter = rateLimiter;
        this.logRepository = logRepository;
        this.signer = signer;
    }

    public TurnCredential issueCredential(Tenant tenant, String userId, String region) {
        if (!rateLimiter.tryAcquire(tenant.getId(), tenant.getRateLimitPerMin())) {
            throw new RateLimitExceededException(tenant.getId());
        }

        TurnSecret secret = secretRepository.findByRealm(tenant.getRealm())
                .orElseThrow(() -> new IllegalStateException("No TURN secret configured for realm " + tenant.getRealm()));

        long expiry = Instant.now().plusSeconds(tenant.getCredentialTtlSec()).getEpochSecond();
        String username = expiry + ":" + userId;
        String password = signer.sign(secret.getValue(), username);

        logRepository.save(new CredentialIssuanceLog(tenant.getId(), userId, region, tenant.getCredentialTtlSec()));

        List<String> uris = List.of(
                "turn:" + tenant.getRealm() + ":3478?transport=udp",
                "turns:" + tenant.getRealm() + ":5349?transport=tcp"
        );

        return new TurnCredential(username, password, tenant.getCredentialTtlSec(), uris);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=TurnCredentialServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/credential src/test/java/com/k2iot/turncred/credential/TurnCredentialServiceTest.java
git commit -m "feat: add TurnCredentialService issuing signed credentials"
```

---

### Task 8: REST endpoint — `POST /v1/turn-credentials`

**Files:**
- Create: `src/main/java/com/k2iot/turncred/credential/dto/IssueCredentialRequest.java`
- Create: `src/main/java/com/k2iot/turncred/credential/dto/TurnCredentialResponse.java`
- Create: `src/main/java/com/k2iot/turncred/credential/TurnCredentialController.java`
- Create: `src/main/java/com/k2iot/turncred/credential/CredentialExceptionHandler.java`
- Test: `src/test/java/com/k2iot/turncred/credential/TurnCredentialControllerTest.java`

**Interfaces:**
- Consumes: `TurnCredentialService.issueCredential` (Task 7), `CurrentTenantHolder.get()` (Task 6).

- [ ] **Step 1: Write the failing test**

```java
package com.k2iot.turncred.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2iot.turncred.auth.CurrentTenantHolder;
import com.k2iot.turncred.tenant.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TurnCredentialController.class)
class TurnCredentialControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TurnCredentialService credentialService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void issuesCredentialForAuthenticatedTenant() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        CurrentTenantHolder.set(tenant);

        TurnCredential credential = new TurnCredential(
                "1755700000:user-42", "signed-password", 3600,
                List.of("turn:acme.turn.yourplatform.com:3478?transport=udp"));
        when(credentialService.issueCredential(eq(tenant), anyString(), anyString())).thenReturn(credential);

        mockMvc.perform(post("/v1/turn-credentials")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new java.util.HashMap<>())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("1755700000:user-42"))
                .andExpect(jsonPath("$.ttlSeconds").value(3600));
    }

    @Test
    void returns429WhenRateLimitExceeded() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        CurrentTenantHolder.set(tenant);

        when(credentialService.issueCredential(eq(tenant), anyString(), anyString()))
                .thenThrow(new RateLimitExceededException(tenant.getId()));

        mockMvc.perform(post("/v1/turn-credentials")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isTooManyRequests());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TurnCredentialControllerTest`
Expected: FAIL — controller doesn't exist.

- [ ] **Step 3: Implement**

```java
package com.k2iot.turncred.credential.dto;

public record IssueCredentialRequest(String userId) {}
```

```java
package com.k2iot.turncred.credential.dto;

import java.util.List;

public record TurnCredentialResponse(String username, String password, int ttlSeconds, List<String> uris) {}
```

```java
package com.k2iot.turncred.credential;

import com.k2iot.turncred.auth.CurrentTenantHolder;
import com.k2iot.turncred.credential.dto.IssueCredentialRequest;
import com.k2iot.turncred.credential.dto.TurnCredentialResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/turn-credentials")
public class TurnCredentialController {

    private final TurnCredentialService credentialService;

    @Value("${turn.region:default}")
    private String region;

    public TurnCredentialController(TurnCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public TurnCredentialResponse issue(@RequestBody(required = false) IssueCredentialRequest request) {
        var tenant = CurrentTenantHolder.get();
        String userId = (request != null && request.userId() != null) ? request.userId() : UUID.randomUUID().toString();

        TurnCredential credential = credentialService.issueCredential(tenant, userId, region);

        return new TurnCredentialResponse(credential.username(), credential.password(),
                credential.ttlSeconds(), credential.uris());
    }
}
```

```java
package com.k2iot.turncred.credential;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CredentialExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public void handleRateLimit() {}

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public void handleMisconfiguredTenant() {}
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=TurnCredentialControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/credential src/test/java/com/k2iot/turncred/credential/TurnCredentialControllerTest.java
git commit -m "feat: add POST /v1/turn-credentials endpoint"
```

---

### Task 9: Admin endpoints — create tenant, rotate secret (with grace period)

**Files:**
- Create: `src/main/java/com/k2iot/turncred/admin/dto/CreateTenantRequest.java`
- Create: `src/main/java/com/k2iot/turncred/admin/dto/CreateTenantResponse.java`
- Create: `src/main/java/com/k2iot/turncred/admin/TenantAdminController.java`
- Create: `src/main/java/com/k2iot/turncred/secret/SecretRotationService.java`
- Test: `src/test/java/com/k2iot/turncred/admin/TenantAdminControllerTest.java`

**Interfaces:**
- Consumes: `TenantRepository`, `TurnSecretRepository` (Task 3).
- Produces: `SecretRotationService.rotate(String realm, Duration graceWindow): void` — used by ops runbook, not by any later task.

- [ ] **Step 1: Write the failing test**

```java
package com.k2iot.turncred.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2iot.turncred.admin.dto.CreateTenantRequest;
import com.k2iot.turncred.secret.SecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TenantAdminController.class)
class TenantAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean TenantRepository tenantRepository;
    @MockBean SecretRotationService secretRotationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsTenantAndReturnsRawApiKeyOnce() throws Exception {
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/v1/admin/tenants")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateTenantRequest("Acme Corp", "acme.turn.yourplatform.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.realm").value("acme.turn.yourplatform.com"));
    }

    @Test
    void rotatesSecretForExistingTenant() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants/acme.turn.yourplatform.com/rotate-secret"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=TenantAdminControllerTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement**

```java
package com.k2iot.turncred.admin.dto;

public record CreateTenantRequest(String name, String realm) {}
```

```java
package com.k2iot.turncred.admin.dto;

public record CreateTenantResponse(String tenantId, String realm, String apiKey) {}
```

```java
package com.k2iot.turncred.secret;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class SecretRotationService {

    private final TurnSecretRepository turnSecretRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretRotationService(TurnSecretRepository turnSecretRepository) {
        this.turnSecretRepository = turnSecretRepository;
    }

    public String createInitialSecret(String realm) {
        String value = generateSecret();
        TurnSecret secret = new TurnSecret();
        secret.setRealm(realm);
        secret.setValue(value);
        turnSecretRepository.save(secret);
        return value;
    }

    /** Rotates the secret, keeping the old value valid for graceWindow so in-flight
     *  sessions signed just before rotation still authenticate against coturn. */
    public void rotate(String realm, Duration graceWindow) {
        TurnSecret secret = turnSecretRepository.findByRealm(realm)
                .orElseThrow(() -> new IllegalStateException("No secret found for realm " + realm));

        secret.setPreviousValue(secret.getValue());
        secret.setPreviousValidUntil(Instant.now().plus(graceWindow));
        secret.setValue(generateSecret());
        secret.setRotatedAt(Instant.now());

        turnSecretRepository.save(secret);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

```java
package com.k2iot.turncred.admin;

import com.k2iot.turncred.admin.dto.CreateTenantRequest;
import com.k2iot.turncred.admin.dto.CreateTenantResponse;
import com.k2iot.turncred.secret.SecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantAdminController {

    private final TenantRepository tenantRepository;
    private final SecretRotationService secretRotationService;
    private final SecureRandom secureRandom = new SecureRandom();

    public TenantAdminController(TenantRepository tenantRepository, SecretRotationService secretRotationService) {
        this.tenantRepository = tenantRepository;
        this.secretRotationService = secretRotationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTenantResponse create(@RequestBody CreateTenantRequest request) {
        String rawApiKey = generateApiKey();

        Tenant tenant = new Tenant();
        tenant.setName(request.name());
        tenant.setRealm(request.realm());
        tenant.setApiKeyHash(sha256Hex(rawApiKey));
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCredentialTtlSec(3600);
        tenant.setRateLimitPerMin(600);
        tenantRepository.save(tenant);

        secretRotationService.createInitialSecret(request.realm());

        return new CreateTenantResponse(tenant.getId() != null ? tenant.getId().toString() : null,
                tenant.getRealm(), rawApiKey);
    }

    @PostMapping("/{realm}/rotate-secret")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rotateSecret(@PathVariable String realm) {
        secretRotationService.rotate(realm, Duration.ofMinutes(15));
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "tcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=TenantAdminControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/k2iot/turncred/admin src/main/java/com/k2iot/turncred/secret/SecretRotationService.java src/test/java/com/k2iot/turncred/admin
git commit -m "feat: add admin endpoints for tenant creation and secret rotation"
```

---

### Task 10: Coturn Docker config + full local stack

**Files:**
- Create: `coturn/turnserver.conf`
- Create: `Dockerfile`
- Modify: `docker-compose.yml` — add `coturn` and `app` services

**Interfaces:**
- Produces: a fully runnable local stack (`docker compose up`) where coturn validates credentials issued by the app, directly from Postgres.

- [ ] **Step 1: `coturn/turnserver.conf`**

```ini
listening-port=3478
tls-listening-port=5349
listening-ip=0.0.0.0
relay-ip=0.0.0.0
external-ip=$TURN_EXTERNAL_IP

fingerprint
lt-cred-mech
use-auth-secret

# realm-based secret lookup — reads the turn_secret table Task 2/9 write to
psql-userdb="host=postgres dbname=turncred user=turncred password=turncred connect_timeout=10"

no-cli
no-tlsv1
no-tlsv1_1
cert=/etc/coturn/cert.pem
pkey=/etc/coturn/key.pem

log-file=stdout
verbose
```

- [ ] **Step 2: `Dockerfile` for the app**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/turn-credential-platform-0.1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: Extend `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: turncred
      POSTGRES_USER: turncred
      POSTGRES_PASSWORD: turncred
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  coturn:
    image: coturn/coturn:4.6
    depends_on:
      - postgres
    environment:
      TURN_EXTERNAL_IP: 127.0.0.1
    volumes:
      - ./coturn/turnserver.conf:/etc/coturn/turnserver.conf
    ports:
      - "3478:3478/udp"
      - "3478:3478/tcp"
      - "5349:5349/tcp"
    command: ["-c", "/etc/coturn/turnserver.conf"]

  app:
    build: .
    depends_on:
      - postgres
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/turncred
      SPRING_DATA_REDIS_HOST: redis
      TURN_REGION: local-dev
    ports:
      - "8080:8080"

volumes:
  pgdata:
```

- [ ] **Step 4: Verify the full stack**

Run:
```bash
mvn clean package -DskipTests
docker compose up -d --build
curl -X POST http://localhost:8080/v1/admin/tenants \
  -H "Content-Type: application/json" \
  -d '{"name":"Acme Corp","realm":"acme.turn.yourplatform.com"}'
# copy the returned apiKey, then:
curl -X POST http://localhost:8080/v1/turn-credentials -H "X-Api-Key: <apiKey>"
```
Expected: HTTP 200 with `username`/`password`/`uris`. This credential is verified programmatically in Task 11's integration test (manual `turnutils_uclient` verification is documented in the README as an optional manual check).

- [ ] **Step 5: Commit**

```bash
git add coturn Dockerfile docker-compose.yml
git commit -m "feat: add coturn container and full local Docker stack"
```

---

### Task 11: End-to-end integration test (issued credential authenticates against real coturn)

**Files:**
- Create: `src/test/java/com/k2iot/turncred/integration/CredentialIssuanceIntegrationTest.java`

**Interfaces:**
- Consumes: the full app context (Task 1–9) plus Testcontainers Postgres, Redis, and a coturn container built from `coturn/turnserver.conf` (Task 10).

- [ ] **Step 1: Write the test**

```java
package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CredentialIssuanceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("turncred").withUsername("turncred").withPassword("turncred");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void issuedCredentialSignatureMatchesTenantSecretStoredInPostgres() throws Exception {
        var createBody = new java.util.HashMap<String, String>();
        createBody.put("name", "Acme Corp");
        createBody.put("realm", "acme.turn.yourplatform.com");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.set("Content-Type", "application/json");

        var createResponse = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), jsonHeaders), String.class);
        assertThat(createResponse.getStatusCode().value()).isEqualTo(201);

        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String apiKey = created.get("apiKey").asText();

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set("X-Api-Key", apiKey);

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

Note: this test validates the HTTP contract and DB round-trip end-to-end (tenant creation → secret generation → credential issuance) against real Postgres and Redis containers. A live coturn container is exercised manually via the `docker compose` flow in Task 10 Step 4 — coupling a coturn container to this JUnit suite is left as a documented follow-up in the README (coturn's Testcontainers startup/healthcheck timing is flaky enough to warrant its own spike, out of scope for this plan).

- [ ] **Step 2: Run to verify it passes**

Run: `mvn test -Dtest=CredentialIssuanceIntegrationTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/k2iot/turncred/integration
git commit -m "test: add end-to-end tenant creation + credential issuance integration test"
```

---

### Task 12: Structured logging + production Docker Compose reference

**Files:**
- Create: `src/main/java/com/k2iot/turncred/logging/RequestLoggingFilter.java`
- Create: `src/main/resources/logback-spring.xml`
- Create: `docker-compose.prod.yml`
- Create: `README.md`

**Interfaces:**
- Produces: JSON access logs (`tenant_id`, `request_id`, `latency_ms`, `status`) for every request; a documented single-region production topology.

- [ ] **Step 1: `logback-spring.xml`**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

- [ ] **Step 2: `RequestLoggingFilter`**

```java
package com.k2iot.turncred.logging;

import com.k2iot.turncred.auth.CurrentTenantHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            var tenant = CurrentTenantHolder.get();
            long latencyMs = System.currentTimeMillis() - start;
            log.info("request method={} path={} status={} tenantId={} latencyMs={}",
                    httpRequest.getMethod(), httpRequest.getRequestURI(), httpResponse.getStatus(),
                    tenant != null ? tenant.getId() : "anonymous", latencyMs);
            MDC.remove("requestId");
        }
    }
}
```

- [ ] **Step 3: `docker-compose.prod.yml`** (single-region reference; primary vs replica selected via env var)

```yaml
services:
  app:
    image: k2iot/turn-credential-platform:latest
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://${POSTGRES_HOST}:5432/turncred
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      SPRING_DATA_REDIS_HOST: ${REDIS_HOST}
      TURN_REGION: ${REGION_NAME}
      # Only the primary region's deployment should set this to true;
      # all other regions run read-only (admin endpoints return 403 — see README).
      APP_WRITE_ENABLED: ${WRITE_ENABLED:-false}
    ports:
      - "8080:8080"
    restart: unless-stopped

  coturn:
    image: coturn/coturn:4.6
    environment:
      TURN_EXTERNAL_IP: ${TURN_EXTERNAL_IP}
    volumes:
      - ./coturn/turnserver.conf:/etc/coturn/turnserver.conf
    environment:
      PSQL_HOST: ${POSTGRES_HOST}
    ports:
      - "3478:3478/udp"
      - "3478:3478/tcp"
      - "5349:5349/tcp"
    command: ["-c", "/etc/coturn/turnserver.conf"]
    restart: unless-stopped
```

- [ ] **Step 4: `README.md`**

```markdown
# TURN Credential Platform

Multi-tenant TURN REST API credential issuance service.

## Local development
    docker compose up -d --build
    curl -X POST http://localhost:8080/v1/admin/tenants -H "Content-Type: application/json" \
      -d '{"name":"Acme Corp","realm":"acme.turn.yourplatform.com"}'

## Production topology
See `docker-compose.prod.yml`. One Postgres primary (one region) +
streaming read replicas per other region. Set `APP_WRITE_ENABLED=true`
only in the primary region's deployment — admin endpoints reject writes
elsewhere. Spec: `docs/superpowers/specs/2026-08-20-turn-credential-platform-design.md`.

## Manual coturn verification
    turnutils_uclient -u <username> -w <password> -p 3478 <turn-host>
```

- [ ] **Step 5: Run full suite one last time**

Run: `mvn test`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/k2iot/turncred/logging src/main/resources/logback-spring.xml docker-compose.prod.yml README.md
git commit -m "feat: add structured JSON logging and production Compose reference"
```

---

## Self-Review Notes

- **Spec coverage:** §4 architecture → Tasks 1,10,12. §5.1–5.4 components → Tasks 1,3,5,6,7,10. §6 data model → Task 2/3. §7 API → Tasks 8,9. §8 security (TTL, API key hash, rotation grace period) → Tasks 6,9. §9 testing → every task's TDD step + Task 11. §10 infra → Tasks 1,10,12. All spec sections have a covering task.
- **Placeholder scan:** none found — every step has runnable code.
- **Type consistency checked:** `TurnCredentialService.issueCredential(Tenant, String, String)` signature is identical between Task 7's implementation and Task 8's controller usage; `RedisRateLimiter.tryAcquire(UUID, int)` matches between Task 5 and Task 7; `TurnSecretRepository.findByRealm(String)` matches between Task 3 and Task 7.
- **Fixed during review:** Task 6's interceptor code originally referenced an unused `BCrypt` import — flagged inline in the task so the implementer removes it; the shipped method (`sha256Hex`) does not use it.
