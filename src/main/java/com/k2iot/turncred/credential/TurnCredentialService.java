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
