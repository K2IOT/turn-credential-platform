package com.k2iot.turncred.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecretRotationServiceTest {

    @Mock
    private TurnSecretRepository secretRepository;

    private SecretRotationService rotationService;

    @BeforeEach
    void setUp() {
        rotationService = new SecretRotationService(secretRepository);
    }

    @Test
    void createInitialSecretSavesNewTurnSecret() {
        String realm = "test.realm.com";

        String secretValue = rotationService.createInitialSecret(realm);

        assertThat(secretValue).isNotBlank();
        verify(secretRepository).save(any(TurnSecret.class));
    }

    @Test
    void rotateUpdatesPreviousValueAndGracePeriod() {
        String realm = "test.realm.com";
        TurnSecret existing = new TurnSecret();
        existing.setRealm(realm);
        existing.setValue("old-secret-value");
        when(secretRepository.findByRealm(realm)).thenReturn(Optional.of(existing));

        rotationService.rotate(realm, Duration.ofMinutes(15));

        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository).save(captor.capture());

        TurnSecret updated = captor.getValue();
        assertThat(updated.getPreviousValue()).isEqualTo("old-secret-value");
        assertThat(updated.getValue()).isNotEqualTo("old-secret-value");
        assertThat(updated.getPreviousValidUntil()).isAfter(Instant.now());
    }
}
