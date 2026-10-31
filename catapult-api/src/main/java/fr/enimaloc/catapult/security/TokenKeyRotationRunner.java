package fr.enimaloc.catapult.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Re-encrypts the stored secrets under the current key at startup while a previous key is set, so that it can
 * then be removed. Starting again is harmless: what is done already is skipped. Each write only applies to the
 * value it read: a token refreshed meanwhile, already under the current key, is never overwritten by its former
 * self, which would lose a refresh token Twitch has since rotated.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TokenKeyRotationRunner implements ApplicationRunner {
    /** Every column holding a value of {@link TokenEncryptionService}. */
    static final List<Secret> SECRETS = List.of(
            new Secret("oauth_token", "id", "access_token"),
            new Secret("oauth_token", "id", "refresh_token"),
            new Secret("user_account", "id", "steam_personal_token"),
            new Secret("twitchat_widget_settings", "user_id", "obs_password_encrypted"),
            new Secret("minecraft_service_account", "id", "msa_refresh_token"));

    record Secret(String table, String id, String column) {}

    private final TokenEncryptionService encryption;
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        if (!encryption.rotating()) return;
        int reencrypted = 0;
        int failed = 0;
        for (Secret secret : SECRETS) {
            try {
                Counts counts = rotate(secret);
                reencrypted += counts.reencrypted();
                failed += counts.failed();
            } catch (DataAccessException e) {
                log.error("Could not re-encrypt {}.{}", secret.table(), secret.column(), e);
                failed++;
            }
        }
        if (failed > 0) {
            log.error("Token key rotation: {} value(s) re-encrypted, {} failed: keep the previous key", reencrypted, failed);
        } else {
            log.info("Token key rotation: {} value(s) re-encrypted, the previous key can be removed", reencrypted);
        }
    }

    private record Counts(int reencrypted, int failed) {}

    private Counts rotate(Secret secret) {
        // The names only come from SECRETS, never from outside.
        String select = "SELECT %s AS id, %s AS secret FROM %s WHERE %s IS NOT NULL"
                .formatted(secret.id(), secret.column(), secret.table(), secret.column());
        String update = "UPDATE %s SET %s = ? WHERE %s = ? AND %s = ?"
                .formatted(secret.table(), secret.column(), secret.id(), secret.column());
        int reencrypted = 0;
        int failed = 0;
        for (Map<String, Object> row : jdbc.queryForList(select)) {
            Object id = row.get("id");
            String stored = (String) row.get("secret");
            try {
                Optional<String> updated = encryption.reencrypted(stored);
                if (updated.isPresent()) reencrypted += jdbc.update(update, updated.get(), id, stored);
            } catch (SecurityException e) {
                // Never the value itself.
                log.warn("Could not read {}.{} of {} with either key", secret.table(), secret.column(), id);
                failed++;
            }
        }
        return new Counts(reencrypted, failed);
    }
}
