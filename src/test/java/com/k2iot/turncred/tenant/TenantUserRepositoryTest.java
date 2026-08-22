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
