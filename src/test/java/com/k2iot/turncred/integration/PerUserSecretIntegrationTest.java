package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.k2iot.turncred.credential.HmacSigner;
import com.k2iot.turncred.secret.TurnSecret;
import com.k2iot.turncred.secret.TurnSecretRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerUserSecretIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TurnSecretRepository secretRepository;

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json");
        h.set("X-Admin-Api-Key", "dev-admin-key");
        return h;
    }

    private JsonNode createTenant(String realm) throws Exception {
        var body = new HashMap<String, String>();
        body.put("name", "Test Tenant");
        body.put("realm", realm);
        var res = restTemplate.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(res.getBody());
    }

    private void registerUser(String tenantId, String userId) throws Exception {
        var body = new HashMap<String, String>();
        body.put("userId", userId);
        var res = restTemplate.postForEntity(
                "/v1/admin/tenants/" + tenantId + "/users",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void registerUser_returns201_andCreatesPerUserSecretRow() throws Exception {
        String realm = "per-user-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();

        registerUser(tenantId, "alice");

        var userSecret = secretRepository.findCurrentByRealmAndUserId(realm, "alice");
        assertThat(userSecret).isPresent();
        assertThat(userSecret.get().getUserId()).isEqualTo("alice");
        assertThat(userSecret.get().getValidUntil()).isNull();
    }

    @Test
    void registerUser_duplicate_returns409() throws Exception {
        String realm = "dup-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        registerUser(tenantId, "alice");

        var body = new HashMap<String, String>();
        body.put("userId", "alice");
        var res = restTemplate.postForEntity(
                "/v1/admin/tenants/" + tenantId + "/users",
                new HttpEntity<>(objectMapper.writeValueAsString(body), adminHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void issueCredential_withRegisteredUser_returns200_signedWithUserSecret() throws Exception {
        String realm = "issue-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        String apiKey = tenant.get("apiKey").asText();
        registerUser(tenantId, "bob");

        TurnSecret userSecret = secretRepository.findCurrentByRealmAndUserId(realm, "bob").orElseThrow();

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "bob");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode cred = objectMapper.readTree(credRes.getBody());
        String expectedPassword = new HmacSigner().sign(userSecret.getValue(), cred.get("username").asText());
        assertThat(cred.get("password").asText()).isEqualTo(expectedPassword);
    }

    @Test
    void issueCredential_withUnregisteredUser_returns403() throws Exception {
        String realm = "unreg-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String apiKey = tenant.get("apiKey").asText();

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "not-registered");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void issueCredential_withSuspendedUser_returns403() throws Exception {
        String realm = "susp-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        String apiKey = tenant.get("apiKey").asText();
        registerUser(tenantId, "carol");

        var deleteRes = restTemplate.exchange(
                "/v1/admin/tenants/" + tenantId + "/users/carol",
                HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), Void.class);
        assertThat(deleteRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "carol");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rotateUserSecret_newIssuanceUsesNewSecret() throws Exception {
        String realm = "rotate-" + UUID.randomUUID() + ".com";
        JsonNode tenant = createTenant(realm);
        String tenantId = tenant.get("tenantId").asText();
        String apiKey = tenant.get("apiKey").asText();
        registerUser(tenantId, "dave");

        TurnSecret initialSecret = secretRepository.findCurrentByRealmAndUserId(realm, "dave").orElseThrow();

        var rotateRes = restTemplate.exchange(
                "/v1/admin/tenants/" + tenantId + "/users/dave/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(adminHeaders()), Void.class);
        assertThat(rotateRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        TurnSecret newSecret = secretRepository.findCurrentByRealmAndUserId(realm, "dave").orElseThrow();
        assertThat(newSecret.getValue()).isNotEqualTo(initialSecret.getValue());
        assertThat(newSecret.getValidUntil()).isNull();

        TurnSecret graceSecret = secretRepository.findById(initialSecret.getId()).orElseThrow();
        assertThat(graceSecret.getValidUntil()).isNotNull();

        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.set("Content-Type", "application/json");
        tenantHeaders.set("X-Api-Key", apiKey);

        var credBody = new HashMap<String, String>();
        credBody.put("userId", "dave");
        var credRes = restTemplate.postForEntity("/v1/turn-credentials",
                new HttpEntity<>(objectMapper.writeValueAsString(credBody), tenantHeaders), String.class);
        assertThat(credRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode cred = objectMapper.readTree(credRes.getBody());
        String expectedPassword = new HmacSigner().sign(newSecret.getValue(), cred.get("username").asText());
        assertThat(cred.get("password").asText()).isEqualTo(expectedPassword);
    }
}
