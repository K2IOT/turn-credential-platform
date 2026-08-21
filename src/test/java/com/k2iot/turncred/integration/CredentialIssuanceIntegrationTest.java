package com.k2iot.turncred.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CredentialIssuanceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("turncred").withUsername("turncred").withPassword("turncred");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        String tenantId = created.get("id").asText();
        var rotateResponse = restTemplate.exchange("/v1/admin/tenants/" + tenantId + "/rotate-secret",
                HttpMethod.POST, new HttpEntity<>(jsonHeaders), Void.class);
        assertThat(rotateResponse.getStatusCode().value()).isEqualTo(204);
    }
}
