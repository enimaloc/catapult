package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TwitchTimeoutFunctionTest {

    @Test
    void invokeTimesOutTheTargetAndReturnsEmptyString() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();

        TwitchTimeoutFunction fn = new TwitchTimeoutFunction(service);
        assertThat(fn.parameterNames()).containsExactly("login", "durationSeconds", "reason");
        assertThat(fn.optionalParameterNames()).containsExactly("reason");
        assertThat(fn.isAction()).isTrue();
        assertThat(fn.invoke(user, new Object[]{"troll", 60.0, "spam"})).isEqualTo("");
        verify(service).timeout(user, "troll", 60, "spam");
    }

    @Test
    void invokeDefaultsReasonToEmptyStringWhenOmitted() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();

        TwitchTimeoutFunction fn = new TwitchTimeoutFunction(service);
        assertThat(fn.invoke(user, new Object[]{"troll", 60.0})).isEqualTo("");
        verify(service).timeout(user, "troll", 60, "");
    }
}
