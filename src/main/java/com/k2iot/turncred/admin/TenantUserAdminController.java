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
        tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        userSecretRotationService.deregisterUser(tenantId, userId);
    }
}
