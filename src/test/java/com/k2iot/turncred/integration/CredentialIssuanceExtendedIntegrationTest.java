package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.credential.CredentialIssuanceLog;
import com.k2iot.turncred.credential.CredentialIssuanceLogRepository;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialIssuanceExtendedIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private CredentialIssuanceLogRepository logRepository;

    @Test
    void issueCredential_WithExplicitUserId_ReturnsFormattedUsernameAndSavesAuditLog() throws Exception {
        var createBody = new HashMap<String, String>();
        createBody.put("name", "Audit Corp");
        createBody.put("realm", "audit.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), adminHeaders), String.class);
        JsonNode tenantJson = objectMapper.readTree(createRes.getBody());
        String apiKey = tenantJson.get("apiKey").asText();
        UUID tenantId = UUID.fromString(tenantJson.get("tenantId").asText());

        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set("X-Api-Key", apiKey);
        clientHeaders.set("Content-Type", "application/json");

        var requestBody = new HashMap<String, String>();
        requestBody.put("userId", "custom-user-999");

        var response = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(requestBody), clientHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode credJson = objectMapper.readTree(response.getBody());
        assertThat(credJson.get("username").asText()).endsWith(":custom-user-999");

        List<CredentialIssuanceLog> logs = logRepository.findAll();
        assertThat(logs).anyMatch(l -> l.getTenantId().equals(tenantId) && "custom-user-999".equals(l.getUserId()));
    }

    @Test
    void issueCredential_Unauthorized_WhenApiKeyInvalidOrMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", "invalid-api-key");

        var response = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueCredential_Unauthorized_WhenTenantIsSuspended() throws Exception {
        var createBody = new HashMap<String, String>();
        createBody.put("name", "Suspended Corp");
        createBody.put("realm", "suspended.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), adminHeaders), String.class);
        JsonNode tenantJson = objectMapper.readTree(createRes.getBody());
        String apiKey = tenantJson.get("apiKey").asText();
        UUID tenantId = UUID.fromString(tenantJson.get("tenantId").asText());

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);

        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set("X-Api-Key", apiKey);

        var response = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(clientHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
