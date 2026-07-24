package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import fr.enimaloc.catapult.service.TwitchUserProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwitchUserDisplayNameFunctionTest {

    @Test
    void invokeReturnsDisplayName() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getUserProfile(user, "myfriend"))
            .thenReturn(Optional.of(new TwitchUserProfile("MyFriend", Instant.EPOCH)));

        TwitchUserDisplayNameFunction fn = new TwitchUserDisplayNameFunction(service);
        assertThat(fn.namespace()).isEqualTo("twitch");
        assertThat(fn.name()).isEqualTo("userDisplayName");
        assertThat(fn.parameterNames()).containsExactly("login");
        assertThat(fn.invoke(user, new Object[]{"myfriend"})).isEqualTo("MyFriend");
    }

    @Test
    void invokeReturnsEmptyStringWhenUserNotFound() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getUserProfile(user, "nobody")).thenReturn(Optional.empty());

        TwitchUserDisplayNameFunction fn = new TwitchUserDisplayNameFunction(service);
        assertThat(fn.invoke(user, new Object[]{"nobody"})).isEqualTo("");
    }
}
