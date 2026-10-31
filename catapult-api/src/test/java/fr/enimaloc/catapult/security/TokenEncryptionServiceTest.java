package fr.enimaloc.catapult.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final String OLD = "old-encryption-key-32-bytes-long!";
    private static final String NEW = "new-encryption-key-32-bytes-long!";

    @Test
    void decrypt_readsWhatThePreviousKeyEncrypted() {
        String stored = new TokenEncryptionService(OLD).encrypt("token");

        assertThat(new TokenEncryptionService(NEW, OLD).decrypt(stored)).isEqualTo("token");
    }

    @Test
    void decrypt_withoutThePreviousKey_fails() {
        String stored = new TokenEncryptionService(OLD).encrypt("token");

        assertThatThrownBy(() -> new TokenEncryptionService(NEW).decrypt(stored)).isInstanceOf(SecurityException.class);
    }

    @Test
    void theEmptyKeyValue_readsWhatNoKeyEncrypted() {
        String stored = new TokenEncryptionService("").encrypt("token");

        assertThat(new TokenEncryptionService(NEW, TokenEncryptionService.EMPTY_KEY).decrypt(stored)).isEqualTo("token");
    }

    @Test
    void anEmptyPreviousKey_meansNoRotation() {
        assertThat(new TokenEncryptionService(NEW, "").rotating()).isFalse();
        assertThat(new TokenEncryptionService(NEW, OLD).rotating()).isTrue();
    }

    @Test
    void rotatingToAnEmptyKey_isRefused() {
        assertThatThrownBy(() -> new TokenEncryptionService("", OLD)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reencrypted_movesThePreviousKeysValue_toTheCurrentKey() {
        TokenEncryptionService rotating = new TokenEncryptionService(NEW, OLD);

        String moved = rotating.reencrypted(new TokenEncryptionService(OLD).encrypt("token")).orElseThrow();

        assertThat(new TokenEncryptionService(NEW).decrypt(moved)).isEqualTo("token");
    }

    @Test
    void reencrypted_leavesTheCurrentKeysValue() {
        TokenEncryptionService rotating = new TokenEncryptionService(NEW, OLD);

        assertThat(rotating.reencrypted(rotating.encrypt("token"))).isEmpty();
    }

    @Test
    void reencrypted_failsOnWhatNeitherKeyReads() {
        TokenEncryptionService rotating = new TokenEncryptionService(NEW, OLD);
        String foreign = new TokenEncryptionService("some-other-key-32-bytes-long-yes!").encrypt("token");

        assertThatThrownBy(() -> rotating.reencrypted(foreign)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> rotating.reencrypted("not base64!")).isInstanceOf(SecurityException.class);
    }
}
