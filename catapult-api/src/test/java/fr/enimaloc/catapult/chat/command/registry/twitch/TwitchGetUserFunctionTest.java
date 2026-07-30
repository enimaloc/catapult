package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwitchGetUserFunctionTest {

    @Test
    void declaresNamespaceAndNoParameters() {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        TwitchGetUserFunction fn = new TwitchGetUserFunction(gateway);

        assertThat(fn.namespace()).isEqualTo("twitch");
        assertThat(fn.name()).isEqualTo("getUser");
        assertThat(fn.parameterNames()).isEmpty();
    }

    @Test
    void invokeReturnsTheBoundUsersTwitchDisplayName() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        UserAccount user = new UserAccount();
        when(gateway.twitchOwnDisplayName(user)).thenReturn(Optional.of("SomeStreamer"));

        TwitchGetUserFunction fn = new TwitchGetUserFunction(gateway);
        assertThat(fn.invoke(user, new Object[0])).isEqualTo("SomeStreamer");
    }

    @Test
    void invokeReturnsNullWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        UserAccount user = new UserAccount();
        when(gateway.twitchOwnDisplayName(user)).thenReturn(Optional.empty());

        TwitchGetUserFunction fn = new TwitchGetUserFunction(gateway);
        assertThat(fn.invoke(user, new Object[0])).isNull();
    }
}
