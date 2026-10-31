package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetPlayerPerspectivesFunctionTest {

    @Test
    void invokeAcceptsABareIgdbId() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbPlayerPerspectives("1234")).thenReturn(Optional.of(List.of("First person")));

        IgdbGetPlayerPerspectivesFunction fn = new IgdbGetPlayerPerspectivesFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getPlayerPerspectives");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(List.of("First person"));
    }

    @Test
    void invokeAcceptsTheGetGameResultObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbPlayerPerspectives("1234")).thenReturn(Optional.of(List.of("First person")));
        Map<String, Object> gameResult = Map.of("id", "1234", "name", "VALORANT");

        IgdbGetPlayerPerspectivesFunction fn = new IgdbGetPlayerPerspectivesFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{gameResult})).isEqualTo(List.of("First person"));
    }

    @Test
    void invokeReturnsEmptyListWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbPlayerPerspectives("9999")).thenReturn(Optional.empty());

        IgdbGetPlayerPerspectivesFunction fn = new IgdbGetPlayerPerspectivesFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(List.of());
    }
}
