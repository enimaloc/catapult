package fr.enimaloc.catapult.security;

import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.UUID;

import fr.enimaloc.catapult.domain.UserAccount;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor("test-jwt-secret-key-32-bytes-long!!".getBytes());

    private static UserAccount user() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId("123");
        account.setTwitchUsername("someuser");
        return account;
    }

    @Test
    void expiryHoursNegative_generatesTokenWithNoExpirationClaim() {
        JwtService service = new JwtService(KEY, -1);

        String token = service.generateForUser(user(), List.of("ROLE_USER"));

        assertThat(service.validate(token).getExpiration()).isNull();
    }

    @Test
    void expiryHoursPositive_generatesTokenWithExpirationClaim() {
        JwtService service = new JwtService(KEY, 24);

        String token = service.generateForUser(user(), List.of("ROLE_USER"));

        assertThat(service.validate(token).getExpiration()).isNotNull();
    }
}
