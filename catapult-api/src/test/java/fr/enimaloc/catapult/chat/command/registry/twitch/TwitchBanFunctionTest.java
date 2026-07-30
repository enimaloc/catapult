package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TwitchBanFunctionTest {

    @Test
    void invokeBansTheTargetAndReturnsEmptyString() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();

        TwitchBanFunction fn = new TwitchBanFunction(service);
        assertThat(fn.parameterNames()).containsExactly("login", "reason");
        assertThat(fn.optionalParameterNames()).containsExactly("reason");
        assertThat(fn.isAction()).isTrue();
        assertThat(fn.invoke(user, new Object[]{"troll", "spam"})).isEqualTo("");
        verify(service).ban(user, "troll", "spam");
    }

    @Test
    void invokeDefaultsReasonToEmptyStringWhenOmitted() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();

        TwitchBanFunction fn = new TwitchBanFunction(service);
        assertThat(fn.invoke(user, new Object[]{"troll"})).isEqualTo("");
        verify(service).ban(user, "troll", "");
    }
}
