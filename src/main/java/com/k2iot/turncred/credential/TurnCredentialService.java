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

    public TurnCredential issueCredential(Tenant tenant, String userId) {
        if (!rateLimiter.tryAcquire(tenant.getId(), tenant.getRateLimitPerMin())) {
            throw new RateLimitExceededException(tenant.getId());
        }

        TurnSecret secret = secretRepository.findByRealm(tenant.getRealm())
                .orElseThrow(() -> new IllegalStateException("No TURN secret configured for realm " + tenant.getRealm()));

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
