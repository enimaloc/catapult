package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwitchUserFollowedAtFunctionTest {

    @Test
    void declaresTheRequiredScope() {
        TwitchChatService service = mock(TwitchChatService.class);
        TwitchUserFollowedAtFunction fn = new TwitchUserFollowedAtFunction(service);
        assertThat(fn.requiredScopes()).containsExactly("moderator:read:followers");
    }

    @Test
    void invokeReturnsFormattedFollowDate() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getFollowedAt(user, "myfriend"))
            .thenReturn(Optional.of(Instant.parse("2020-01-15T00:00:00Z")));

        TwitchUserFollowedAtFunction fn = new TwitchUserFollowedAtFunction(service);
        assertThat(fn.invoke(user, new Object[]{"myfriend"})).isEqualTo("2020-01-15");
    }

    @Test
    void invokeReturnsEmptyStringWhenNotFollowing() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getFollowedAt(user, "stranger")).thenReturn(Optional.empty());

        TwitchUserFollowedAtFunction fn = new TwitchUserFollowedAtFunction(service);
        assertThat(fn.invoke(user, new Object[]{"stranger"})).isEqualTo("");
    }
}
