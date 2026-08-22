package com.k2iot.turncred.admin;

import com.k2iot.turncred.admin.dto.RegisterUserRequest;
import com.k2iot.turncred.admin.dto.RegisterUserResponse;
import com.k2iot.turncred.secret.UserSecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TenantUserAdminControllerTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserSecretRotationService userSecretRotationService = mock(UserSecretRotationService.class);
    private final TenantUserAdminController controller =
            new TenantUserAdminController(tenantRepository, userSecretRotationService);

    private Tenant tenantWithRealm(UUID id, String realm) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setRealm(realm);
        return t;
    }

    @Test
    void register_callsRegisterUserWithCorrectArgs() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(tenantWithRealm(tenantId, "acme.turn.com")));

        RegisterUserResponse response = controller.register(tenantId, new RegisterUserRequest("alice"));

        verify(userSecretRotationService).registerUser(tenantId, "acme.turn.com", "alice");
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.tenantId()).isEqualTo(tenantId.toString());
    }

    @Test
    void register_throwsNotFoundWhenTenantMissing() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.register(tenantId, new RegisterUserRequest("alice")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotateUserSecret_callsRotateWithGracePeriod() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(tenantWithRealm(tenantId, "acme.turn.com")));

        controller.rotateUserSecret(tenantId, "alice");

        verify(userSecretRotationService).rotateUserSecret("acme.turn.com", "alice", Duration.ofMinutes(15));
    }

    @Test
    void rotateUserSecret_throwsNotFoundWhenTenantMissing() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.rotateUserSecret(tenantId, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deregisterUser_callsDeregister() {
        UUID tenantId = UUID.randomUUID();

        controller.deregisterUser(tenantId, "alice");

        verify(userSecretRotationService).deregisterUser(tenantId, "alice");
    }
}
