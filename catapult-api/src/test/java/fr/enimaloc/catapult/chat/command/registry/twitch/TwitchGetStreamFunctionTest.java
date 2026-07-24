package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import fr.enimaloc.catapult.service.TwitchStreamInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwitchGetStreamFunctionTest {

    @Test
    void invokeReturnsStreamFieldsWhenOnline() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        Instant startedAt = Instant.now().minus(Duration.ofHours(2).plusMinutes(34));
        when(service.getStreamInfo(user)).thenReturn(
            Optional.of(new TwitchStreamInfo("My Title", "Just Chatting", 42, startedAt)));

        TwitchGetStreamFunction fn = new TwitchGetStreamFunction(service);
        assertThat(fn.namespace()).isEqualTo("twitch");
        assertThat(fn.name()).isEqualTo("getStream");
        assertThat(fn.parameterNames()).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("title")).isEqualTo("My Title");
        assertThat(result.get("category")).isEqualTo("Just Chatting");
        assertThat(result.get("viewers")).isEqualTo(42);
        assertThat(result.get("uptime")).isEqualTo("2h34m");
    }

    @Test
    void invokeReturnsEmptyFieldsWhenOffline() throws Exception {
        TwitchChatService service = mock(TwitchChatService.class);
        UserAccount user = new UserAccount();
        when(service.getStreamInfo(user)).thenReturn(Optional.empty());

        TwitchGetStreamFunction fn = new TwitchGetStreamFunction(service);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("title")).isEqualTo("");
        assertThat(result.get("category")).isEqualTo("");
        assertThat(result.get("viewers")).isEqualTo("");
        assertThat(result.get("uptime")).isEqualTo("");
    }
}
