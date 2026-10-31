package fr.enimaloc.catapult.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;

/**
 * AES-256-GCM encryption of the OAuth2 tokens stored in the database. While a previous key is set, what
 * the current key cannot read is read with it, until {@link TokenKeyRotationRunner} has re-encrypted it.
 */
@Slf4j
@Service
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final Random RANDOM = new SecureRandom();

    /** The previous key's value for a database encrypted without any key set (an all-zero key). */
    static final String EMPTY_KEY = "__EMPTY__";

    private final SecretKey secretKey;
    private final SecretKey previousKey;

    public TokenEncryptionService(String encryptionKey) {
        this(encryptionKey, "");
    }

    @Autowired
    public TokenEncryptionService(@Value("${app.security.token-encryption-key}") String encryptionKey,
                                  @Value("${app.security.token-encryption-previous-key:}") String previousEncryptionKey) {
        boolean rotating = !previousEncryptionKey.isEmpty();
        if (encryptionKey.isEmpty()) {
            if (rotating) throw new IllegalStateException("Cannot rotate the token encryption key to an empty key");
            log.warn("app.security.token-encryption-key is not configured: the stored tokens are not protected");
        }
        this.secretKey = key(encryptionKey);
        this.previousKey = rotating ? key(EMPTY_KEY.equals(previousEncryptionKey) ? "" : previousEncryptionKey) : null;
    }

    private static SecretKey key(String encryptionKey) {
        byte[] keyBytes = encryptionKey.getBytes(StandardCharsets.UTF_8);
        // Expects a 32-byte (256-bit) key
        byte[] paddedKey = new byte[32];
        System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, 32));
        return new SecretKeySpec(paddedKey, "AES");
    }

    /** Whether a previous key is set, so that stored values may have to be re-encrypted. */
    boolean rotating() {
        return previousKey != null;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new SecurityException("Failed to encrypt token", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            try {
                return decrypt(encrypted, secretKey);
            } catch (AEADBadTagException e) {
                if (previousKey == null) throw e;
                return decrypt(encrypted, previousKey);
            }
        } catch (Exception e) {
            throw new SecurityException("Failed to decrypt token", e);
        }
    }

    /**
     * {@code encrypted} under the current key when the previous one encrypted it; empty when the current
     * one already did.
     *
     * @throws SecurityException when neither key reads it
     */
    Optional<String> reencrypted(String encrypted) {
        try {
            decrypt(encrypted, secretKey);
            return Optional.empty();
        } catch (AEADBadTagException e) {
            return Optional.of(encrypt(decrypt(encrypted)));
        } catch (Exception e) {
            throw new SecurityException("Failed to decrypt token", e);
        }
    }

    private static String decrypt(String encrypted, SecretKey key) throws Exception {
        byte[] data = Base64.getDecoder().decode(encrypted);

        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
