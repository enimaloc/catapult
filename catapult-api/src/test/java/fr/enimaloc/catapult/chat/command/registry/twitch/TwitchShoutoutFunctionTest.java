package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TwitchShoutoutFunctionTest {

    @Test
    void declaresTheRequiredScope() {
        TwitchChatService service = mock(TwitchChatService.class);
        TwitchShoutoutFunction fn = new TwitchShoutoutFunction(service);
        assertThat(fn.requiredScopes()).containsExactly("moderator:manage:shoutouts");
    }

    @Test
    void invokeShoutsOutTheTargetAndReturnsEmptyString() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();

        TwitchShoutoutFunction fn = new TwitchShoutoutFunction(service);
        assertThat(fn.parameterNames()).containsExactly("login");
        assertThat(fn.isAction()).isTrue();
        assertThat(fn.invoke(user, new Object[]{"myfriend"})).isEqualTo("");
        verify(service).shoutout(user, "myfriend");
    }
}
