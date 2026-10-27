package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserApiToken;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserApiTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A member's personal API token, which only {@code /api/v2/custom-bindings/**} accepts: {@value #PREFIX} followed by
 * 32 {@link SecureRandom} bytes in base64url without padding. Only its SHA-256 (hex) is stored, so the token is shown
 * once, when it is generated; generating another replaces it, and the old one stops working at once.
 */
@Service
@RequiredArgsConstructor
public class UserApiTokenService {

    public static final String PREFIX = "cat_";
    private static final int TOKEN_BYTES = 32;
    /** {@value #PREFIX} and 32 bytes as base64url without padding (43 characters). */
    private static final Pattern FORMAT = Pattern.compile(Pattern.quote(PREFIX) + "[A-Za-z0-9_-]{43}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserApiTokenRepository repository;
    private final UserAccountRepository userAccountRepository;

    /** A new token for {@code user}, replacing theirs if any: the only time it is ever shown. */
    @Transactional
    public ApiTokenCreatedDto regenerate(UserAccount user) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Microseconds: what the database keeps, so status() later answers the same instant
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UserApiToken stored = repository.findById(user.getId())
                .orElseGet(() -> new UserApiToken(user.getId(), null, null));
        stored.setTokenHash(hash(token));
        stored.setCreatedAt(now);
        repository.save(stored);
        return new ApiTokenCreatedDto(token, now);
    }

    /** The member {@code token} belongs to; empty for anything that is not a current personal token. */
    @Transactional(readOnly = true)
    public Optional<UserAccount> authenticate(String token) {
        if (token == null || !FORMAT.matcher(token).matches()) return Optional.empty();
        String hash = hash(token);
        return repository.findByTokenHash(hash)
                // The lookup already matched; compared again in constant time all the same
                .filter(stored -> MessageDigest.isEqual(
                        stored.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                        hash.getBytes(StandardCharsets.US_ASCII)))
                .flatMap(stored -> userAccountRepository.findById(stored.getUserId()));
    }

    @Transactional(readOnly = true)
    public ApiTokenStatusDto status(UserAccount user) {
        return repository.findById(user.getId())
                .map(stored -> new ApiTokenStatusDto(true, stored.getCreatedAt()))
                .orElseGet(() -> new ApiTokenStatusDto(false, null));
    }

    /** {@code user}'s token stops working; nothing happens when they have none. */
    @Transactional
    public void revoke(UserAccount user) {
        repository.findById(user.getId()).ifPresent(repository::delete);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
