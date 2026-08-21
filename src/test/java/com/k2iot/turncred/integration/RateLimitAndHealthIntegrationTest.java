package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitAndHealthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void issueCredential_RateLimitExceeded_Returns429TooManyRequests() throws Exception {
        var createBody = new HashMap<String, String>();
        createBody.put("name", "Limited Corp");
        createBody.put("realm", "limited.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), adminHeaders), String.class);
        JsonNode tenantJson = objectMapper.readTree(createRes.getBody());
        String apiKey = tenantJson.get("apiKey").asText();
        UUID tenantId = UUID.fromString(tenantJson.get("tenantId").asText());

        // Set low rate limit threshold (e.g. 2 requests per minute)
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setRateLimitPerMin(2);
        tenantRepository.save(tenant);

        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set("X-Api-Key", apiKey);
        clientHeaders.set("Content-Type", "application/json");

        // Request 1: OK
        var res1 = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST, new HttpEntity<>(clientHeaders), String.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Request 2: OK
        var res2 = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST, new HttpEntity<>(clientHeaders), String.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Request 3: Exceeded -> 429 TOO_MANY_REQUESTS
        var res3 = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST, new HttpEntity<>(clientHeaders), String.class);
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void actuatorHealth_ReturnsStatusUpWithPostgresAndRedisConnected() throws Exception {
        var response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.get("status").asText()).isEqualTo("UP");
    }
}
