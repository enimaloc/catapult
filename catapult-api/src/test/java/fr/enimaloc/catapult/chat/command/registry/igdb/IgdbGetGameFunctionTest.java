package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway.IgdbGame;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetGameFunctionTest {

    @Test
    void invokeReturnsTheFullGameObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        IgdbGame game = new IgdbGame("1234", "VALORANT", "A tactical shooter.", "2020-06-02",
            "85", "80", "PC, PlayStation 5", "https://www.igdb.com/games/valorant");
        when(gateway.igdbGame("Valorant")).thenReturn(Optional.of(game));

        IgdbGetGameFunction fn = new IgdbGetGameFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getGame");
        assertThat(fn.parameterNames()).containsExactly("query");
        assertThat(fn.returnKeys()).containsExactly(
            "id", "name", "summary", "releaseDate", "rating", "criticRating", "platforms", "igdbUrl");

        Object result = fn.invoke(null, new Object[]{"Valorant"});

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("id", "1234")
            .containsEntry("name", "VALORANT")
            .containsEntry("summary", "A tactical shooter.")
            .containsEntry("releaseDate", "2020-06-02")
            .containsEntry("rating", "85")
            .containsEntry("criticRating", "80")
            .containsEntry("platforms", "PC, PlayStation 5")
            .containsEntry("igdbUrl", "https://www.igdb.com/games/valorant");
    }

    @Test
    void invokeReturnsAnAllBlankObjectWhenGatewayFindsNothing() throws Exception {
        // Deliberately not Map.of() (bare empty map) — a missing key reads as JS `undefined`
        // through the sandbox, and `undefined == ""` is false exactly like a real value would
        // be, so a DSL command checking `igdb#getGame(q).name == ""` needs every field to
        // actually be present and blank, not absent, for that check to work at all.
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbGame("Unknown")).thenReturn(Optional.empty());

        IgdbGetGameFunction fn = new IgdbGetGameFunction(gateway);
        Object result = fn.invoke(null, new Object[]{"Unknown"});

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsOnlyKeys(
            "id", "name", "summary", "releaseDate", "rating", "criticRating", "platforms", "igdbUrl");
        assertThat(map.values()).allMatch(""::equals);
    }
}
