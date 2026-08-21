package com.k2iot.turncred.credential;

import com.k2iot.turncred.ratelimit.RedisRateLimiter;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretId;
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
        TurnSecret secret = new TurnSecret(new TurnSecretId(tenant.getRealm(), "super-secret"));

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(secretRepository.findCurrentByRealm(tenant.getRealm())).thenReturn(Optional.of(secret));

        TurnCredential credential = service.issueCredential(tenant, "user-42");

        assertThat(credential.username()).endsWith(":user-42");
        assertThat(credential.password()).isEqualTo(new HmacSigner().sign("super-secret", credential.username()));
        assertThat(credential.ttlSeconds()).isEqualTo(3600);
        verify(logRepository).save(any(CredentialIssuanceLog.class));
    }

    @Test
    void throwsWhenRateLimitExceeded() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(false);

        assertThatThrownBy(() -> service.issueCredential(tenant, "user-42"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void throwsWhenTenantHasNoSecretConfigured() {
        Tenant tenant = tenantWithRealm("orphan.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(secretRepository.findCurrentByRealm(tenant.getRealm())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCredential(tenant, "user-42"))
                .isInstanceOf(IllegalStateException.class);
    }
}
