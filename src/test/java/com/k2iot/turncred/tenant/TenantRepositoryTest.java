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
