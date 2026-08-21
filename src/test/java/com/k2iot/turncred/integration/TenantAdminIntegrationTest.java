package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.secret.TurnSecretRepository;
import com.k2iot.turncred.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TurnSecretRepository secretRepository;

    @Test
    void createTenant_Success_PersistsTenantAndInitialSecret() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Beta Corp");
        body.put("realm", "beta.turn.yourplatform.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "dev-admin-key");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("tenantId")).isTrue();
        assertThat(json.get("realm").asText()).isEqualTo("beta.turn.yourplatform.com");
        assertThat(json.get("apiKey").asText()).startsWith("tcp_");

        UUID tenantId = UUID.fromString(json.get("tenantId").asText());
        assertThat(tenantRepository.findById(tenantId)).isPresent();
        assertThat(secretRepository.findCurrentByRealm("beta.turn.yourplatform.com")).isPresent();
    }

    @Test
    void createTenant_Unauthorized_WhenAdminApiKeyMissingOrInvalid() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Unauthorized Corp");
        body.put("realm", "unauth.turn.yourplatform.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "wrong-admin-key");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createTenant_BadRequest_WhenFieldsAreBlank() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "");
        body.put("realm", " ");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "dev-admin-key");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTenant_Conflict_WhenRealmAlreadyExists() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Duplicate Corp 1");
        body.put("realm", "duplicate.turn.yourplatform.com");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-Admin-Api-Key", "dev-admin-key");

        restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        var duplicateBody = new HashMap<String, String>();
        duplicateBody.put("name", "Duplicate Corp 2");
        duplicateBody.put("realm", "duplicate.turn.yourplatform.com");

        var response = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(duplicateBody), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
