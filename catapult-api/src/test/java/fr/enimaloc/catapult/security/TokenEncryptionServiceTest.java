package fr.enimaloc.catapult.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEncryptionServiceTest {

    @Test
    void encryptAndDecrypt_roundtrip() {
        TokenEncryptionService service = new TokenEncryptionService("test-encryption-key-32bytes!!");

        String plaintext = "test-access-token-value";
        String encrypted = service.encrypt(plaintext);
        String decrypted = service.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptProducesDifferentOutputEachTime() {
        TokenEncryptionService service = new TokenEncryptionService("test-encryption-key-32bytes!!");

        String enc1 = service.encrypt("same-token");
        String enc2 = service.encrypt("same-token");

        assertThat(enc1).isNotEqualTo(enc2);
    }
}
