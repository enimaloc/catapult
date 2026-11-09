package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetVideosFunctionTest {

    private static final List<Map<String, Object>> VIDEOS =
        List.of(Map.of("name", "Trailer", "url", "https://www.youtube.com/watch?v=abc123"));

    @Test
    void invokeResolvesIdFromEitherABareIdOrTheGameObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbVideos("1234")).thenReturn(Optional.of(VIDEOS));

        IgdbGetVideosFunction fn = new IgdbGetVideosFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getVideos");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(VIDEOS);
        assertThat(fn.invoke(null, new Object[]{Map.of("id", "1234")})).isEqualTo(VIDEOS);
    }

    @Test
    void invokeReturnsEmptyListWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbVideos("9999")).thenReturn(Optional.empty());

        IgdbGetVideosFunction fn = new IgdbGetVideosFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(List.of());
    }
}
