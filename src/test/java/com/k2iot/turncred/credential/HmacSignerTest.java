package com.k2iot.turncred.credential;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();

    @Test
    void signsMessageMatchingReferenceHmacSha1Implementation() throws Exception {
        String secret = "tenant-secret-abc";
        String message = "1755700000:user-123";

        String actual = signer.sign(secret, message);
        String expected = referenceHmacSha1(secret, message);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        String message = "1755700000:user-123";
        String sigA = signer.sign("secret-a", message);
        String sigB = signer.sign("secret-b", message);
        assertThat(sigA).isNotEqualTo(sigB);
    }

    private String referenceHmacSha1(String secret, String message) throws NoSuchAlgorithmException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
