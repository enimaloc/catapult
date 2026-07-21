package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    void invokeThrowsBecauseContextIsBoundPerCallBySandboxExecutor() {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        TwitchGetUserFunction fn = new TwitchGetUserFunction(gateway);

        assertThatThrownBy(() -> fn.invoke(new Object[0]))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
