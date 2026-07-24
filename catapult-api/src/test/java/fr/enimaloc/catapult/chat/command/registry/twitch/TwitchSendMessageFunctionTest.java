package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TwitchSendMessageFunctionTest {

    @Test
    void invokeSendsTheMessageAndReturnsEmptyString() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();

        TwitchSendMessageFunction fn = new TwitchSendMessageFunction(service);
        assertThat(fn.parameterNames()).containsExactly("text");
        assertThat(fn.invoke(user, new Object[]{"hello chat"})).isEqualTo("");
        verify(service).sendMessage(user, "hello chat");
    }
}
