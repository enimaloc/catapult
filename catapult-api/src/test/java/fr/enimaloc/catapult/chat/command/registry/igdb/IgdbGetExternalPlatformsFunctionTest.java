package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetExternalPlatformsFunctionTest {

    @Test
    void invokeAcceptsABareIgdbId() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbExternalPlatforms("1234")).thenReturn(Optional.of(Map.of("steam", "1234560")));

        IgdbGetExternalPlatformsFunction fn = new IgdbGetExternalPlatformsFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getExternalPlatforms");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(Map.of("steam", "1234560"));
    }

    @Test
    void invokeAcceptsTheGetGameResultObject() throws Exception {
        // What actually flows through the sandbox: IgdbGetGameFunction.invoke() returns a
        // Map (via DtoMapper), not the raw IgdbGame record — igdb#getGame(q).id is how a
        // streamer would chain it into igdb#getExternalPlatforms.
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbExternalPlatforms("1234")).thenReturn(Optional.of(Map.of("steam", "1234560")));
        Map<String, Object> gameResult = Map.of("id", "1234", "name", "VALORANT");

        IgdbGetExternalPlatformsFunction fn = new IgdbGetExternalPlatformsFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{gameResult})).isEqualTo(Map.of("steam", "1234560"));
    }

    @Test
    void invokeReturnsEmptyMapWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbExternalPlatforms("9999")).thenReturn(Optional.empty());

        IgdbGetExternalPlatformsFunction fn = new IgdbGetExternalPlatformsFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(Map.of());
    }
}
