package com.k2iot.turncred.auth;

import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantAuthInterceptorTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantAuthInterceptor interceptor = new TenantAuthInterceptor(tenantRepository);

    @Test
    void allowsRequestWithValidApiKey() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findByApiKeyHash(anyString())).thenReturn(Optional.of(tenant));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "valid-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(CurrentTenantHolder.get()).isEqualTo(tenant);
        CurrentTenantHolder.clear();
    }

    @Test
    void rejectsRequestWithMissingApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsSuspendedTenant() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findByApiKeyHash(anyString())).thenReturn(Optional.of(tenant));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "suspended-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
