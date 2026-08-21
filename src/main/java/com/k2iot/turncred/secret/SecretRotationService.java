package com.k2iot.turncred.secret;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class SecretRotationService {

    private final TurnSecretRepository turnSecretRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretRotationService(TurnSecretRepository turnSecretRepository) {
        this.turnSecretRepository = turnSecretRepository;
    }

    public String createInitialSecret(String realm) {
        String value = generateSecret();
        TurnSecret secret = new TurnSecret();
        secret.setRealm(realm);
        secret.setValue(value);
        turnSecretRepository.save(secret);
        return value;
    }

    public void rotate(String realm, Duration graceWindow) {
        TurnSecret secret = turnSecretRepository.findByRealm(realm)
                .orElseThrow(() -> new IllegalStateException("No secret found for realm " + realm));

        secret.setPreviousValue(secret.getValue());
        secret.setPreviousValidUntil(Instant.now().plus(graceWindow));
        secret.setValue(generateSecret());
        secret.setRotatedAt(Instant.now());

        turnSecretRepository.save(secret);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
