package com.k2iot.turncred.credential;

import com.k2iot.turncred.ratelimit.RedisRateLimiter;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretId;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantUser;
import com.k2iot.turncred.tenant.TenantUserRepository;
import com.k2iot.turncred.tenant.TenantUserStatus;
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
    private final TenantUserRepository tenantUserRepository = mock(TenantUserRepository.class);

    private final TurnCredentialService service =
            new TurnCredentialService(secretRepository, rateLimiter, logRepository, signer, tenantUserRepository);

    private Tenant tenantWithRealm(String realm) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setRealm(realm);
        tenant.setCredentialTtlSec(3600);
        tenant.setRateLimitPerMin(600);
        return tenant;
    }

    @Test
    void issuesCredentialSignedWithUserSecret() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        TenantUser activeUser = new TenantUser(tenant.getId(), "user-42");
        TurnSecret secret = new TurnSecret(new TurnSecretId(tenant.getRealm(), "user-42", "super-secret"));

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "user-42"))
                .thenReturn(Optional.of(activeUser));
        when(secretRepository.findCurrentByRealmAndUserId(tenant.getRealm(), "user-42"))
                .thenReturn(Optional.of(secret));

        TurnCredential credential = service.issueCredential(tenant, "user-42");

        assertThat(credential.username()).endsWith(":user-42");
        assertThat(credential.password()).isEqualTo(new HmacSigner().sign("super-secret", credential.username()));
        assertThat(credential.ttlSeconds()).isEqualTo(3600);
        verify(logRepository).save(any(CredentialIssuanceLog.class));
        verify(secretRepository, never()).findCurrentByRealm(any());
    }

    @Test
    void throwsWhenRateLimitExceeded() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(false);

        assertThatThrownBy(() -> service.issueCredential(tenant, "user-42"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void throws403WhenUserNotRegistered() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCredential(tenant, "unknown"))
                .isInstanceOf(UserNotRegisteredException.class);
    }

    @Test
    void throws403WhenUserIsSuspended() {
        Tenant tenant = tenantWithRealm("acme.turn.yourplatform.com");
        TenantUser suspended = new TenantUser(tenant.getId(), "alice");
        suspended.setStatus(TenantUserStatus.SUSPENDED);

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "alice"))
                .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.issueCredential(tenant, "alice"))
                .isInstanceOf(UserNotRegisteredException.class);
    }

    @Test
    void throwsWhenNoSecretConfiguredForRegisteredUser() {
        Tenant tenant = tenantWithRealm("orphan.turn.yourplatform.com");
        TenantUser activeUser = new TenantUser(tenant.getId(), "alice");

        when(rateLimiter.tryAcquire(tenant.getId(), 600)).thenReturn(true);
        when(tenantUserRepository.findByTenantIdAndUserId(tenant.getId(), "alice"))
                .thenReturn(Optional.of(activeUser));
        when(secretRepository.findCurrentByRealmAndUserId(tenant.getRealm(), "alice"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCredential(tenant, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }
}
