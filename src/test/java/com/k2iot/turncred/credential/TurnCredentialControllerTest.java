package com.k2iot.turncred.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2iot.turncred.auth.CurrentTenantHolder;
import com.k2iot.turncred.tenant.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TurnCredentialController.class)
class TurnCredentialControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TurnCredentialService credentialService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void issuesCredentialForAuthenticatedTenant() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        CurrentTenantHolder.set(tenant);

        TurnCredential credential = new TurnCredential(
                "1755700000:user-42", "signed-password", 3600,
                List.of("turn:acme.turn.yourplatform.com:3478?transport=udp"));
        when(credentialService.issueCredential(eq(tenant), anyString())).thenReturn(credential);

        mockMvc.perform(post("/v1/turn-credentials")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new java.util.HashMap<>())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("1755700000:user-42"))
                .andExpect(jsonPath("$.ttlSeconds").value(3600));
    }

    @Test
    void returns429WhenRateLimitExceeded() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        CurrentTenantHolder.set(tenant);

        when(credentialService.issueCredential(eq(tenant), anyString()))
                .thenThrow(new RateLimitExceededException(tenant.getId()));

        mockMvc.perform(post("/v1/turn-credentials")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isTooManyRequests());
    }
}
