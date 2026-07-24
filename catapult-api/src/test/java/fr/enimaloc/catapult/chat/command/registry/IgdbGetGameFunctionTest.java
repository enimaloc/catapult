package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetGameFunctionTest {

    @Test
    void invokeReturnsResolvedGameName() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbGameName("Valorant")).thenReturn(Optional.of("VALORANT"));

        IgdbGetGameFunction fn = new IgdbGetGameFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getGame");
        assertThat(fn.parameterNames()).containsExactly("query");
        assertThat(fn.invoke(null, new Object[]{"Valorant"})).isEqualTo("VALORANT");
    }

    @Test
    void invokeReturnsNullWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbGameName("Unknown")).thenReturn(Optional.empty());

        IgdbGetGameFunction fn = new IgdbGetGameFunction(gateway);
        assertThat(fn.invoke(null, new Object[]{"Unknown"})).isNull();
    }
}
