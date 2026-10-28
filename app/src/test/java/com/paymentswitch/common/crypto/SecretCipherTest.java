package com.paymentswitch.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final SecretCipher cipher = new SecretCipher(KEY);

    @Test
    void roundTrips() {
        String ciphertext = cipher.encrypt("sk_test_abc");

        assertThat(ciphertext).startsWith("v1:").doesNotContain("sk_test_abc");
        assertThat(cipher.decrypt(ciphertext)).isEqualTo("sk_test_abc");
    }

    @Test
    void sameInputEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void tamperedCiphertextIsDetected() {
        String ciphertext = cipher.encrypt("sk_test_abc");
        byte[] raw = Base64.getDecoder().decode(ciphertext.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherKeyCannotDecrypt() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        SecretCipher other = new SecretCipher(Base64.getEncoder().encodeToString(otherKey));

        assertThatThrownBy(() -> other.decrypt(cipher.encrypt("sk_test_abc")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeysThatAreNot256Bits() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCipher(shortKey)).isInstanceOf(IllegalStateException.class);
    }
}
