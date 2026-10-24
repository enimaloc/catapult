package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiUserResolverTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final ApiUserResolver resolver = new ApiUserResolver(users);

    private static UserAccount account(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        account.setTwitchId(username + "-id");
        return account;
    }

    private static Jwt jwtFor(UserAccount account) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(account.getId().toString()).claim("twitchId", account.getTwitchId()).build();
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void viewer_bySubject() {
        UserAccount me = account("me");
        when(users.findById(me.getId())).thenReturn(Optional.of(me));

        assertThat(resolver.viewer(jwtFor(me))).isSameAs(me);
    }

    @Test
    void viewer_unknownIsUnauthorized() {
        assertStatus(() -> resolver.viewer(jwtFor(account("ghost"))), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void viewerByTwitchId() {
        UserAccount me = account("me");
        when(users.findByTwitchId("me-id")).thenReturn(Optional.of(me));

        assertThat(resolver.viewerByTwitchId(jwtFor(me))).isSameAs(me);
        assertStatus(() -> resolver.viewerByTwitchId(jwtFor(account("ghost"))), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noTokenIsUnauthorized() {
        assertStatus(() -> resolver.viewer(null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> resolver.viewerByTwitchId(null), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void channel_unknownIsNotFound() {
        assertStatus(() -> resolver.channel("nope"), HttpStatus.NOT_FOUND);
    }
}
