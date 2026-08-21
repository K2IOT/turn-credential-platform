package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
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

class SecretRotationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TurnSecretRepository secretRepository;

    @Test
    void rotateSecret_Success_TransitionsOldSecretToGracePeriodAndCreatesNewCurrentSecret() throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Rotation Corp");
        body.put("realm", "rotation.turn.yourplatform.com");

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("Content-Type", "application/json");
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        var createRes = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders), String.class);
        JsonNode json = objectMapper.readTree(createRes.getBody());
        String tenantId = json.get("tenantId").asText();

        TurnSecret initialSecret = secretRepository.findCurrentByRealm("rotation.turn.yourplatform.com").orElseThrow();

        var rotateRes = restTemplate.exchange("/v1/admin/tenants/" + tenantId + "/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(adminHeaders), Void.class);
        assertThat(rotateRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<TurnSecret> activeSecrets = secretRepository.findValidByRealm("rotation.turn.yourplatform.com");
        assertThat(activeSecrets).hasSize(2);

        TurnSecret newCurrentSecret = secretRepository.findCurrentByRealm("rotation.turn.yourplatform.com").orElseThrow();
        assertThat(newCurrentSecret.getId().getValue()).isNotEqualTo(initialSecret.getId().getValue());
        assertThat(newCurrentSecret.getValidUntil()).isNull();
    }

    @Test
    void rotateSecret_NotFound_WhenTenantIdDoesNotExist() {
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Admin-Api-Key", "dev-admin-key");

        UUID nonExistentId = UUID.randomUUID();
        var rotateRes = restTemplate.exchange("/v1/admin/tenants/" + nonExistentId + "/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(adminHeaders), Void.class);

        assertThat(rotateRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
