package com.k2iot.turncred.admin;

import com.k2iot.turncred.util.HashUtil;
import com.k2iot.turncred.admin.dto.CreateTenantRequest;
import com.k2iot.turncred.admin.dto.CreateTenantResponse;
import com.k2iot.turncred.secret.SecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import jakarta.validation.Valid;
import java.util.UUID;

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
    public CreateTenantResponse create(@RequestBody @Valid CreateTenantRequest request) {
        String rawApiKey = generateApiKey();

        Tenant tenant = new Tenant();
        tenant.setName(request.name());
        tenant.setRealm(request.realm());
        tenant.setApiKeyHash(HashUtil.sha256Hex(rawApiKey));
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCredentialTtlSec(3600);
        tenant.setRateLimitPerMin(600);
        tenantRepository.save(tenant);

        secretRotationService.createInitialSecret(request.realm());

        return new CreateTenantResponse(tenant.getId() != null ? tenant.getId().toString() : null,
                tenant.getRealm(), rawApiKey);
    }

    @PostMapping("/{id}/rotate-secret")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rotateSecret(@PathVariable UUID id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        secretRotationService.rotate(tenant.getRealm(), Duration.ofMinutes(15));
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "tcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
