package fr.enimaloc.catapult.chat.command.registry.tw;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwHasFunctionTest {

    @Test
    void invokeReturnsTrueWhenTheGivenTwIsActive() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        GameContext ctx = new GameContext(null, "1877", "Game", null, LocalDate.now(), Map.of(),
            null, null, Set.of("violence_graphic"), Map.of("violence_graphic", "Violence (graphic)"),
            null, null, null, List.of(), List.of(), List.of());
        when(gameContextService.get(user)).thenReturn(Optional.of(ctx));

        TwHasFunction fn = new TwHasFunction(gameContextService);
        assertThat(fn.namespace()).isEqualTo("tw");
        assertThat(fn.name()).isEqualTo("has");
        assertThat(fn.parameterNames()).containsExactly("name");
        assertThat(fn.invoke(user, new Object[]{"violence_graphic"})).isEqualTo(true);
    }

    @Test
    void invokeReturnsFalseWhenTheGivenTwIsNotActive() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        GameContext ctx = new GameContext(null, "1877", "Game", null, LocalDate.now(), Map.of(),
            null, null, Set.of("death_of_animal"), Map.of("death_of_animal", "Death of animal"),
            null, null, null, List.of(), List.of(), List.of());
        when(gameContextService.get(user)).thenReturn(Optional.of(ctx));

        TwHasFunction fn = new TwHasFunction(gameContextService);
        assertThat(fn.invoke(user, new Object[]{"violence_graphic"})).isEqualTo(false);
    }

    @Test
    void invokeReturnsFalseWhenNoGameIsDetected() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        TwHasFunction fn = new TwHasFunction(gameContextService);
        assertThat(fn.invoke(user, new Object[]{"violence_graphic"})).isEqualTo(false);
    }
}
