package fr.enimaloc.catapult.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable opaque identifier for an API key, used in admin payloads so the raw
 * secret never leaves the server. 64-bit truncated SHA-256 — collision-free
 * for any realistic pool size (≪ 4 billion keys).
 */
final class ApiKeyHasher {
    private ApiKeyHasher() {}

    static String id(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
