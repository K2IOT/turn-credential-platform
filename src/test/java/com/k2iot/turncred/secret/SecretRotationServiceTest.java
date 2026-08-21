package com.k2iot.turncred.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void createInitialSecretSavesNewRowWithNullValidUntil() {
        String realm = "test.realm.com";

        String value = rotationService.createInitialSecret(realm);

        assertThat(value).isNotBlank();
        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository).save(captor.capture());
        TurnSecret saved = captor.getValue();
        assertThat(saved.getRealm()).isEqualTo(realm);
        assertThat(saved.getValue()).isEqualTo(value);
        assertThat(saved.getValidUntil()).isNull();
    }

    @Test
    void rotateMarksPreviousSecretExpiringAndInsertsNewCurrent() {
        String realm = "test.realm.com";
        TurnSecret current = new TurnSecret(new TurnSecretId(realm, "old-secret"));

        when(secretRepository.findCurrentByRealm(realm)).thenReturn(Optional.of(current));

        rotationService.rotate(realm, Duration.ofMinutes(15));

        // Verify cleanup ran
        verify(secretRepository).deleteExpiredForRealm(realm);

        // Capture all save calls
        ArgumentCaptor<TurnSecret> captor = ArgumentCaptor.forClass(TurnSecret.class);
        verify(secretRepository, times(2)).save(captor.capture());
        List<TurnSecret> saved = captor.getAllValues();

        // First save: old secret gets validUntil set
        TurnSecret expiring = saved.get(0);
        assertThat(expiring.getValue()).isEqualTo("old-secret");
        assertThat(expiring.getValidUntil()).isAfter(Instant.now());

        // Second save: new current secret with null validUntil
        TurnSecret newSecret = saved.get(1);
        assertThat(newSecret.getValue()).isNotEqualTo("old-secret");
        assertThat(newSecret.getValidUntil()).isNull();
    }

    @Test
    void rotateThrowsWhenNoCurrentSecretExists() {
        when(secretRepository.findCurrentByRealm("missing.realm")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rotationService.rotate("missing.realm", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.realm");
    }
}
