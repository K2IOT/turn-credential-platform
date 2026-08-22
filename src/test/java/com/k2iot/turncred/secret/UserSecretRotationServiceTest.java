package com.k2iot.turncred.secret;

import com.k2iot.turncred.tenant.TenantUser;
import com.k2iot.turncred.tenant.TenantUserRepository;
import com.k2iot.turncred.tenant.TenantUserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSecretRotationServiceTest {

    @Mock private TurnSecretRepository secretRepository;
    @Mock private TenantUserRepository tenantUserRepository;

    private UserSecretRotationService service;

    @BeforeEach
    void setUp() {
        service = new UserSecretRotationService(secretRepository, tenantUserRepository);
    }

    @Test
    void registerUser_createsTenantUserRowAndInitialSecret() {
        UUID tenantId = UUID.randomUUID();

        service.registerUser(tenantId, "acme.turn.com", "alice");

        ArgumentCaptor<TenantUser> userCaptor = ArgumentCaptor.forClass(TenantUser.class);
        verify(tenantUserRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(userCaptor.getValue().getUserId()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(TenantUserStatus.ACTIVE);

        ArgumentCaptor<TurnSecret> secretCaptor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository).save(secretCaptor.capture());
        assertThat(secretCaptor.getValue().getRealm()).isEqualTo("acme.turn.com");
        assertThat(secretCaptor.getValue().getUserId()).isEqualTo("alice");
        assertThat(secretCaptor.getValue().getValidUntil()).isNull();
        assertThat(secretCaptor.getValue().getValue()).isNotBlank();
    }

    @Test
    void rotateUserSecret_expiresCurrent_andInsertsNewCurrent() {
        TurnSecret current = new TurnSecret(new TurnSecretId("acme.turn.com", "alice", "old-secret"));
        when(secretRepository.findCurrentByRealmAndUserId("acme.turn.com", "alice"))
                .thenReturn(Optional.of(current));

        service.rotateUserSecret("acme.turn.com", "alice", Duration.ofMinutes(15));

        verify(secretRepository).deleteExpiredForRealmAndUserId("acme.turn.com", "alice");

        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository, times(2)).save(captor.capture());
        List<TurnSecret> saved = captor.getAllValues();

        assertThat(saved.get(0).getValue()).isEqualTo("old-secret");
        assertThat(saved.get(0).getValidUntil()).isAfter(Instant.now());
        assertThat(saved.get(1).getUserId()).isEqualTo("alice");
        assertThat(saved.get(1).getValidUntil()).isNull();
        assertThat(saved.get(1).getValue()).isNotEqualTo("old-secret");
    }

    @Test
    void rotateUserSecret_throwsWhenNoCurrentSecretExists() {
        when(secretRepository.findCurrentByRealmAndUserId("realm.com", "bob"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotateUserSecret("realm.com", "bob", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bob");
    }

    @Test
    void deregisterUser_setsTenantUserToSuspended() {
        UUID tenantId = UUID.randomUUID();
        TenantUser user = new TenantUser(tenantId, "alice");
        when(tenantUserRepository.findByTenantIdAndUserId(tenantId, "alice"))
                .thenReturn(Optional.of(user));

        service.deregisterUser(tenantId, "alice");

        assertThat(user.getStatus()).isEqualTo(TenantUserStatus.SUSPENDED);
        verify(tenantUserRepository).save(user);
    }

    @Test
    void deregisterUser_throwsWhenUserNotFound() {
        UUID tenantId = UUID.randomUUID();
        when(tenantUserRepository.findByTenantIdAndUserId(tenantId, "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deregisterUser(tenantId, "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }
}
