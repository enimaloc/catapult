package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The API's JWT decoder takes a fictitious account's token under the mock profile only. */
class FictitiousAccountTokenValidatorTest {

    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private final JwtService jwtService = new JwtService(key, -1);

    private JwtDecoder decoder(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new ApiSecurityConfig().jwtDecoder(key, environment);
    }

    private String token(String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setTwitchUsername("someone");
        return jwtService.generateForUser(account, List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void outside_mock_a_fictitious_token_is_refused() {
        assertThatThrownBy(() -> decoder("dev").decode(token("mock-a1b2c3d4")))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("mock profile");
        assertThatThrownBy(() -> decoder().decode(token("mock-a1b2c3d4")))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void under_mock_a_fictitious_token_is_accepted() {
        assertThat(decoder("dev", "mock").decode(token("mock-a1b2c3d4")).getClaimAsString("twitchId"))
                .isEqualTo("mock-a1b2c3d4");
    }

    @Test
    void a_real_token_is_accepted_in_both() {
        assertThat(decoder("dev").decode(token("12345")).getSubject()).isNotBlank();
        assertThat(decoder("dev", "mock").decode(token("12345")).getSubject()).isNotBlank();
    }
}
