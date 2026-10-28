package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A member's personal API tokens, at most one per {@link ApiTokenScope}: {@code cat_} (full) or {@code catr_}
 * (read-only), followed by 32 {@link SecureRandom} bytes in base64url without padding. Only the SHA-256 (hex) is
 * stored, so a token is shown once, when it is generated; generating another of the same scope replaces it, and the
 * old one stops working at once. The prefix decides which scope a token is checked against.
 */
@Service
@RequiredArgsConstructor
public class UserApiTokenService {

    private static final int TOKEN_BYTES = 32;
    /** 32 bytes as base64url without padding (43 characters). */
    private static final String SUFFIX = "[A-Za-z0-9_-]{43}";
    private static final Map<ApiTokenScope, Pattern> FORMATS = formats();

    private static Map<ApiTokenScope, Pattern> formats() {
        Map<ApiTokenScope, Pattern> formats = new EnumMap<>(ApiTokenScope.class);
        for (ApiTokenScope scope : ApiTokenScope.values()) {
            formats.put(scope, Pattern.compile(Pattern.quote(scope.prefix()) + SUFFIX));
        }
        return formats;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserApiTokenRepository repository;
    private final UserAccountRepository userAccountRepository;

    /** A new token of {@code scope} for {@code user}, replacing theirs if any: the only time it is ever shown. */
    @Transactional
    public ApiTokenCreatedDto regenerate(UserAccount user, ApiTokenScope scope) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = scope.prefix() + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Microseconds: what the database keeps, so status() later answers the same instant
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UserApiToken stored = repository.findByUserIdAndScope(user.getId(), scope)
                .orElseGet(() -> new UserApiToken(user.getId(), scope, null, null));
        stored.setTokenHash(hash(token));
        stored.setCreatedAt(now);
        repository.save(stored);
        return new ApiTokenCreatedDto(token, now);
    }

    /**
     * The member {@code token} belongs to and its scope; empty for anything that is not a current personal token.
     * A token only matches the scope its prefix names.
     */
    @Transactional(readOnly = true)
    public Optional<ApiTokenOwner> authenticate(String token) {
        if (token == null) return Optional.empty();
        return FORMATS.entrySet().stream()
                .filter(format -> format.getValue().matcher(token).matches())
                .map(Map.Entry::getKey)
                .findFirst()
                .flatMap(scope -> authenticate(token, scope));
    }

    private Optional<ApiTokenOwner> authenticate(String token, ApiTokenScope scope) {
        String hash = hash(token);
        return repository.findByTokenHash(hash)
                .filter(stored -> stored.getScope() == scope)
                // The lookup already matched; compared again in constant time all the same
                .filter(stored -> MessageDigest.isEqual(
                        stored.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                        hash.getBytes(StandardCharsets.US_ASCII)))
                .flatMap(stored -> userAccountRepository.findById(stored.getUserId()))
                .map(user -> new ApiTokenOwner(user, scope));
    }

    @Transactional(readOnly = true)
    public ApiTokenStatusDto status(UserAccount user, ApiTokenScope scope) {
        return repository.findByUserIdAndScope(user.getId(), scope)
                .map(stored -> new ApiTokenStatusDto(true, stored.getCreatedAt()))
                .orElseGet(() -> new ApiTokenStatusDto(false, null));
    }

    /** {@code user}'s token of {@code scope} stops working; nothing happens when they have none. */
    @Transactional
    public void revoke(UserAccount user, ApiTokenScope scope) {
        repository.findByUserIdAndScope(user.getId(), scope).ifPresent(repository::delete);
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
