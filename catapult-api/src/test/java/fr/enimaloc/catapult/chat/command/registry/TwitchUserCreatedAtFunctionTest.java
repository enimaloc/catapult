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

class TwitchUserCreatedAtFunctionTest {

    @Test
    void invokeReturnsFormattedDate() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getUserProfile(user, "myfriend"))
            .thenReturn(Optional.of(new TwitchUserProfile("MyFriend", Instant.parse("2015-06-01T00:00:00Z"))));

        TwitchUserCreatedAtFunction fn = new TwitchUserCreatedAtFunction(service);
        assertThat(fn.name()).isEqualTo("userCreatedAt");
        assertThat(fn.invoke(user, new Object[]{"myfriend"})).isEqualTo("2015-06-01");
    }

    @Test
    void invokeReturnsEmptyStringWhenUserNotFound() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getUserProfile(user, "nobody")).thenReturn(Optional.empty());

        TwitchUserCreatedAtFunction fn = new TwitchUserCreatedAtFunction(service);
        assertThat(fn.invoke(user, new Object[]{"nobody"})).isEqualTo("");
    }
}
