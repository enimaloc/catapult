package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetCoverFunctionTest {

    private static final Map<String, Object> COVER =
        Map.of("url", "//images.igdb.com/cover.jpg", "width", 264, "height", 352);

    @Test
    void invokeResolvesIdFromEitherABareIdOrTheGameObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbCover("1234")).thenReturn(Optional.of(COVER));

        IgdbGetCoverFunction fn = new IgdbGetCoverFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getCover");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(COVER);
        assertThat(fn.invoke(null, new Object[]{Map.of("id", "1234")})).isEqualTo(COVER);
    }

    @Test
    void invokeReturnsEmptyMapWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbCover("9999")).thenReturn(Optional.empty());

        IgdbGetCoverFunction fn = new IgdbGetCoverFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(Map.of());
    }
}
