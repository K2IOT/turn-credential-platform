package com.k2iot.turncred.secret;

import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TurnSecretRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TurnSecretRepository repo;

    @Autowired
    TenantRepository tenantRepo;

    private void createTenant(String realm) {
        if (tenantRepo.findAll().stream().noneMatch(t -> t.getRealm().equals(realm))) {
            Tenant t = new Tenant();
            t.setName(realm);
            t.setRealm(realm);
            t.setApiKeyHash("hash-" + realm);
            t.setStatus(TenantStatus.ACTIVE);
            t.setCredentialTtlSec(3600);
            t.setRateLimitPerMin(60);
            tenantRepo.save(t);
        }
    }

    private void saveSecret(String realm, String value, Instant validUntil) {
        createTenant(realm);
        TurnSecret s = new TurnSecret(new TurnSecretId(realm, value));
        s.setValidUntil(validUntil);
        repo.save(s);
    }

    @Test
    void findCurrentByRealmReturnsNullValidUntilRowOnly() {
        saveSecret("r1", "current-val", null);
        saveSecret("r1", "grace-val", Instant.now().plusSeconds(3600));

        Optional<TurnSecret> found = repo.findCurrentByRealm("r1");

        assertThat(found).isPresent();
        assertThat(found.get().getValue()).isEqualTo("current-val");
        assertThat(found.get().getValidUntil()).isNull();
    }

    @Test
    void findCurrentByRealmReturnsEmptyWhenNoCurrentSecret() {
        saveSecret("r2", "grace-val", Instant.now().plusSeconds(3600));

        Optional<TurnSecret> found = repo.findCurrentByRealm("r2");

        assertThat(found).isEmpty();
    }

    @Test
    void findValidByRealmExcludesExpiredRows() {
        saveSecret("r3", "current-val", null);
        saveSecret("r3", "grace-val", Instant.now().plusSeconds(3600));
        saveSecret("r3", "expired-val", Instant.now().minusSeconds(1));

        List<TurnSecret> valid = repo.findValidByRealm("r3");

        assertThat(valid).hasSize(2);
        assertThat(valid.stream().map(TurnSecret::getValue))
                .containsExactlyInAnyOrder("current-val", "grace-val");
    }

    @Test
    void deleteExpiredForRealmDeletesOnlyPastRows() {
        saveSecret("r4", "current-val", null);
        saveSecret("r4", "expired-val", Instant.now().minusSeconds(1));

        repo.deleteExpiredForRealm("r4");
        repo.flush();

        List<TurnSecret> remaining = repo.findAll();
        assertThat(remaining.stream().map(TurnSecret::getValue))
                .contains("current-val")
                .doesNotContain("expired-val");
    }

    @Test
    void findCurrentByRealmAndUserId_returnsPerUserSecret() {
        createTenant("realm.test");
        TurnSecret userSecret = new TurnSecret(new TurnSecretId("realm.test", "user-secret-val"), "alice");
        repo.save(userSecret);

        Optional<TurnSecret> found = repo.findCurrentByRealmAndUserId("realm.test", "alice");

        assertThat(found).isPresent();
        assertThat(found.get().getValue()).isEqualTo("user-secret-val");
        assertThat(found.get().getUserId()).isEqualTo("alice");
    }

    @Test
    void findCurrentByRealmAndUserId_doesNotReturnRealmLevelSecret() {
        createTenant("realm2.test");
        TurnSecret realmSecret = new TurnSecret(new TurnSecretId("realm2.test", "realm-val"));
        repo.save(realmSecret);

        Optional<TurnSecret> found = repo.findCurrentByRealmAndUserId("realm2.test", "bob");

        assertThat(found).isEmpty();
    }

    @Test
    void deleteExpiredForRealmAndUserId_removesOnlyThatUsersExpiredRows() {
        createTenant("realm3.test");
        TurnSecret aliceExpired = new TurnSecret(new TurnSecretId("realm3.test", "alice-old"), "alice");
        aliceExpired.setValidUntil(Instant.now().minusSeconds(60));
        repo.save(aliceExpired);

        TurnSecret bobExpired = new TurnSecret(new TurnSecretId("realm3.test", "bob-old"), "bob");
        bobExpired.setValidUntil(Instant.now().minusSeconds(60));
        repo.save(bobExpired);

        repo.deleteExpiredForRealmAndUserId("realm3.test", "alice");
        repo.flush();

        assertThat(repo.findById(new TurnSecretId("realm3.test", "alice-old"))).isEmpty();
        assertThat(repo.findById(new TurnSecretId("realm3.test", "bob-old"))).isPresent();
    }
}

