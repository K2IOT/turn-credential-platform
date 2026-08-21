package com.k2iot.turncred.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByApiKeyHash(String apiKeyHash);
    Optional<Tenant> findByRealm(String realm);
}
