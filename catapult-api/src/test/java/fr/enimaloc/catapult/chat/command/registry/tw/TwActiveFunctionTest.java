package fr.enimaloc.catapult.chat.command.registry.tw;

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

class TwActiveFunctionTest {

    @Test
    void invokeReturnsSortedJoinedLabels() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        GameContext ctx = new GameContext(null, "1877", "Game", null, LocalDate.now(), Map.of(),
            null, null, Set.of("violence_graphic", "death_of_animal"),
            Map.of("violence_graphic", "Violence (graphic)", "death_of_animal", "Death of animal"),
            null, null, null, List.of(), List.of(), List.of());
        when(gameContextService.get(user)).thenReturn(Optional.of(ctx));

        TwActiveFunction fn = new TwActiveFunction(gameContextService);
        assertThat(fn.namespace()).isEqualTo("tw");
        assertThat(fn.name()).isEqualTo("active");
        assertThat(fn.parameterNames()).isEmpty();
        assertThat(fn.invoke(user, new Object[0])).isEqualTo("Death of animal, Violence (graphic)");
    }

    @Test
    void invokeReturnsEmptyStringWhenNoneActive() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        GameContext ctx = new GameContext(null, null, "Game", null, null, Collections.emptyMap(),
            null, null, Set.of(), Map.of(), null, null, null, List.of(), List.of(), List.of());
        when(gameContextService.get(user)).thenReturn(Optional.of(ctx));

        TwActiveFunction fn = new TwActiveFunction(gameContextService);
        assertThat(fn.invoke(user, new Object[0])).isEqualTo("");
    }

    @Test
    void invokeReturnsEmptyStringWhenNoGameIsDetected() throws Exception {
        GameContextService gameContextService = mock(GameContextService.class);
        UserAccount user = new UserAccount();
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        TwActiveFunction fn = new TwActiveFunction(gameContextService);
        assertThat(fn.invoke(user, new Object[0])).isEqualTo("");
    }
}
