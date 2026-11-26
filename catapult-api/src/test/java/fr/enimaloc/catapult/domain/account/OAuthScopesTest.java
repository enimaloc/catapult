package fr.enimaloc.catapult.domain.account;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Twitch answers its scopes as a JSON array, which Spring's token response converter splits as "[a," "b]". */
class OAuthScopesTest {

    @Test
    void joinsTheScopesSpringReadFromTwitchsArray() {
        assertThat(OAuthScopes.parse(OAuthScopes.join(List.of("[user:read:email,", "channel:read:guest_star]"))))
                .containsExactlyInAnyOrder("user:read:email", "channel:read:guest_star");
        assertThat(OAuthScopes.join(List.of("[channel:read:guest_star]"))).isEqualTo("channel:read:guest_star");
    }

    @Test
    void parsesAStoredValue_brokenOrNot() {
        assertThat(OAuthScopes.parse("[user:read:email, channel:read:guest_star]"))
                .isEqualTo(Set.of("user:read:email", "channel:read:guest_star"));
        assertThat(OAuthScopes.parse("user:read:email channel:read:guest_star"))
                .isEqualTo(Set.of("user:read:email", "channel:read:guest_star"));
        assertThat(OAuthScopes.parse(null)).isEmpty();
        assertThat(OAuthScopes.parse("  ")).isEmpty();
    }
}
