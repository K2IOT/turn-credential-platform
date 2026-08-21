package com.k2iot.turncred.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2iot.turncred.admin.dto.CreateTenantRequest;
import com.k2iot.turncred.secret.SecretRotationService;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TenantAdminController.class)
class TenantAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean TenantRepository tenantRepository;
    @MockBean SecretRotationService secretRotationService;
    @MockBean com.k2iot.turncred.auth.AdminAuthInterceptor adminAuthInterceptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsTenantAndReturnsRawApiKeyOnce() throws Exception {
        when(adminAuthInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/v1/admin/tenants")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateTenantRequest("Acme Corp", "acme.turn.yourplatform.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.realm").value("acme.turn.yourplatform.com"));
    }

    @Test
    void rotatesSecretForExistingTenant() throws Exception {
        when(adminAuthInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        mockMvc.perform(post("/v1/admin/tenants/acme.turn.yourplatform.com/rotate-secret"))
                .andExpect(status().isNoContent());
    }
}
