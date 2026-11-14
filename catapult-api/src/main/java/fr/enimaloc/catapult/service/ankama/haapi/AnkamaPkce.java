package fr.enimaloc.catapult.service.ankama.haapi;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE helpers for the launcher-style Ankama login (public client 102, loopback redirect). */
public final class AnkamaPkce {

    public record Pair(String proofKey, String challenge) {
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final int PROOF_KEY_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AnkamaPkce() {
    }

    public static Pair generate() {
        StringBuilder proofKey = new StringBuilder(PROOF_KEY_LENGTH);
        for (int i = 0; i < PROOF_KEY_LENGTH; i++) {
            proofKey.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new Pair(proofKey.toString(), challengeOf(proofKey.toString()));
    }

    static String challengeOf(String proofKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(proofKey.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String authorizeUrl(String challenge) {
        return "https://auth.ankama.com/login/ankama?code_challenge=" + challenge
                + "&redirect_uri=http://127.0.0.1:9001/authorized&client_id=102&direct=true"
                + "&origin_tracker=https://www.ankama-launcher.com/launcher";
    }

    /** Extracts the {@code code} of the launcher redirect URL the user pasted. */
    public static String codeFrom(String pastedUrl) {
        URI uri;
        try {
            uri = URI.create(pastedUrl == null ? "" : pastedUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not the launcher redirect URL");
        }
        if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 9001 || !"/authorized".equals(uri.getPath())) {
            throw new IllegalArgumentException("not the launcher redirect URL");
        }
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String code = params.getFirst("code");
        if (params.containsKey("error") || code == null || code.isBlank()) {
            throw new IllegalArgumentException("no authorization code in the pasted URL");
        }
        return code;
    }
}
