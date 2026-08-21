package com.k2iot.turncred.secret;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class SecretRotationService {

    private final TurnSecretRepository secretRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretRotationService(TurnSecretRepository secretRepository) {
        this.secretRepository = secretRepository;
    }

    @Transactional
    public String createInitialSecret(String realm) {
        String value = generateSecret();
        TurnSecret secret = new TurnSecret(new TurnSecretId(realm, value));
        secretRepository.save(secret);
        return value;
    }

    @Transactional
    public void rotate(String realm, Duration graceWindow) {
        // 1. Remove any rows that have already expired for this realm
        secretRepository.deleteExpiredForRealm(realm);

        // 2. Mark the current secret as expiring
        TurnSecret current = secretRepository.findCurrentByRealm(realm)
                .orElseThrow(() -> new IllegalStateException("No current secret for realm " + realm));
        current.setValidUntil(Instant.now().plus(graceWindow));
        secretRepository.save(current);
        // Flush immediately so the UPDATE reaches the DB before we insert the new current row.
        // Without this, Hibernate may batch the INSERT before the UPDATE, violating uq_turn_secret_current.
        secretRepository.flush();

        // 3. Insert new current secret (valid_until = NULL)
        TurnSecret next = new TurnSecret(new TurnSecretId(realm, generateSecret()));
        secretRepository.save(next);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
