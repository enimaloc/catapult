package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetCurrentGameFunctionTest {

    @Test
    void invokeReturnsTheEnrichedFieldsForTheCurrentGame() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        GameContext ctx = new GameContext(null, "1877", "Valorant", "A tactical shooter",
            LocalDate.of(2020, 6, 2), Map.of("steam", "https://store/730", "official", "https://valorant.com"),
            "https://store/730", "valorant", Set.of(), Map.of(), "PEGI 12",
            85.0, 90.0, List.of("PC"), List.of(), List.of());
        when(gameContextService.get(user)).thenReturn(Optional.of(ctx));

        IgdbGetCurrentGameFunction fn = new IgdbGetCurrentGameFunction(gameContextService);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getCurrentGame");
        assertThat(fn.parameterNames()).isEmpty();
        assertThat(fn.returnKeys()).containsExactly("name", "summary", "releaseDate", "storeUrl",
            "storeSteamUrl", "storeXboxUrl", "storeBattlenetUrl", "storeOfficialUrl",
            "igdbUrl", "ageRating", "rating", "criticRating", "platforms");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("name")).isEqualTo("Valorant");
        assertThat(result.get("summary")).isEqualTo("A tactical shooter");
        assertThat(result.get("releaseDate")).isEqualTo("2020-06-02");
        assertThat(result.get("storeUrl")).isEqualTo("https://store/730");
        assertThat(result.get("storeSteamUrl")).isEqualTo("https://store/730");
        assertThat(result.get("storeXboxUrl")).isEqualTo("");
        assertThat(result.get("storeBattlenetUrl")).isEqualTo("");
        assertThat(result.get("storeOfficialUrl")).isEqualTo("https://valorant.com");
        assertThat(result.get("igdbUrl")).isEqualTo("https://www.igdb.com/games/valorant");
        assertThat(result.get("ageRating")).isEqualTo("PEGI 12");
        assertThat(result.get("rating")).isEqualTo("85");
        assertThat(result.get("criticRating")).isEqualTo("90");
        assertThat(result.get("platforms")).isEqualTo("PC");
    }

    @Test
    void invokeReturnsEmptyFieldsWhenNoGameIsDetected() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        IgdbGetCurrentGameFunction fn = new IgdbGetCurrentGameFunction(gameContextService);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        result.values().forEach(v -> assertThat(v).isEqualTo(""));
    }

    @Test
    void invokeReturnsEmptyFieldsWhenIgdbHasNoDataForTheGame() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        GameContext ctx = new GameContext(null, null, "Unknown Game", null, null,
            Collections.emptyMap(), null, null, Set.of(), Map.of(), null,
            null, null, List.of(), List.of(), List.of());
        when(gameContextService.get(user)).thenReturn(Optional.of(ctx));

        IgdbGetCurrentGameFunction fn = new IgdbGetCurrentGameFunction(gameContextService);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("name")).isEqualTo("Unknown Game");
        assertThat(result.get("summary")).isEqualTo("");
        assertThat(result.get("releaseDate")).isEqualTo("");
        assertThat(result.get("igdbUrl")).isEqualTo("");
        assertThat(result.get("platforms")).isEqualTo("");
    }
}
