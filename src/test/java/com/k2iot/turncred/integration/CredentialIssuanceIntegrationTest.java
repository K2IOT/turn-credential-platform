package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialIssuanceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void issuedCredentialSignatureMatchesTenantSecretStoredInPostgres() throws Exception {
        var createBody = new java.util.HashMap<String, String>();
        createBody.put("name", "Acme Corp");
        createBody.put("realm", "acme.turn.yourplatform.com");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.set("Content-Type", "application/json");
        jsonHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createResponse = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), jsonHeaders), String.class);
        assertThat(createResponse.getStatusCode().value()).isEqualTo(201);

        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String apiKey = created.get("apiKey").asText();

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set("X-Api-Key", apiKey);
        authHeaders.set("Content-Type", "application/json");

        var credResponse = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(authHeaders), String.class);

        assertThat(credResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode credential = objectMapper.readTree(credResponse.getBody());
        assertThat(credential.get("username").asText()).contains(":");
        assertThat(credential.get("password").asText()).isNotBlank();
        assertThat(credential.get("ttlSeconds").asInt()).isEqualTo(3600);
    }

    @Test
    void afterRotationIssuanceStillWorksWithNewCurrentSecret() throws Exception {
        var createBody = new java.util.HashMap<String, String>();
        createBody.put("name", "Rotation Test Corp");
        createBody.put("realm", "rot.turn.yourplatform.com");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.set("Content-Type", "application/json");
        jsonHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createResponse = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(createBody), jsonHeaders), String.class);
        assertThat(createResponse.getStatusCode().value()).isEqualTo(201);

        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String tenantId = created.get("tenantId").asText();
        String apiKey = created.get("apiKey").asText();

        // Rotate secret — old secret enters grace period, new current secret inserted
        var rotateResponse = restTemplate.exchange(
                "/v1/admin/tenants/" + tenantId + "/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(jsonHeaders), Void.class);
        assertThat(rotateResponse.getStatusCode().value()).isEqualTo(204);

        // Credential issuance must succeed with the new current secret
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set("X-Api-Key", apiKey);
        authHeaders.set("Content-Type", "application/json");

        var credResponse = restTemplate.exchange("/v1/turn-credentials", HttpMethod.POST,
                new HttpEntity<>(authHeaders), String.class);
        assertThat(credResponse.getStatusCode().value()).isEqualTo(200);

        JsonNode credential = objectMapper.readTree(credResponse.getBody());
        assertThat(credential.get("username").asText()).contains(":");
        assertThat(credential.get("password").asText()).isNotBlank();
        assertThat(credential.get("ttlSeconds").asInt()).isEqualTo(3600);
    }
}

