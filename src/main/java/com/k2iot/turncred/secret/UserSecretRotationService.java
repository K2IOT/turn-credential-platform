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
