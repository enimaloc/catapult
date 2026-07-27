package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugCommandTest {

    private TwPlaceholderRegistry twRegistry;
    private GameContextService gameContextService;
    private DebugCommand command;
    private UserAccount user;

    @BeforeEach
    void setup() {
        twRegistry = mock(TwPlaceholderRegistry.class);
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("spiders"));
        gameContextService = mock(GameContextService.class);
        PlaceholderResolver resolver = new PlaceholderResolver(new SimpleMeterRegistry(), twRegistry);
        command = new DebugCommand(resolver, twRegistry, gameContextService);

        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private GameContext contextWithGame() {
        return new GameContext(null, "42", "Celeste", null, null,
            Map.of("steam", "https://store.steampowered.com/app/504230"),
            null, null, Set.of("spiders"), Map.of("spiders", "Araignées"), null,
            null, null, List.of(), List.of(), List.of());
    }

    @Test
    void no_args_lists_all_placeholders_with_values() {
        when(gameContextService.get(user)).thenReturn(Optional.of(contextWithGame()));

        String result = (String) command.execute(user, List.of());

        assertThat(result)
            .contains("game#name=Celeste")
            .contains("game#store#steam=https://store.steampowered.com/app/504230")
            .contains("game#summary=" + DebugCommand.EMPTY_VALUE)
            .contains("tw#active=Araignées")
            .contains("tw#spiders=Araignées");
    }

    @Test
    void no_context_lists_all_placeholders_empty() {
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        String result = (String) command.execute(user, List.of());

        assertThat(result).contains("game#name=" + DebugCommand.EMPTY_VALUE);
        for (String path : PlaceholderResolver.KNOWN_PATHS) {
            assertThat(result).contains(path + "=");
        }
    }

    @Test
    void single_arg_resolves_only_that_placeholder() {
        when(gameContextService.get(user)).thenReturn(Optional.of(contextWithGame()));

        String result = (String) command.execute(user, List.of("game#name"));

        assertThat(result).isEqualTo("game#name=Celeste");
    }

    @Test
    void unknown_path_reports_error() {
        when(gameContextService.get(user)).thenReturn(Optional.empty());

        String result = (String) command.execute(user, List.of("game#nope"));

        assertThat(result).isEqualTo("Placeholder inconnu : game#nope");
    }

    @Test
    void is_owner_only_and_hidden_permission_everyone() {
        assertThat(command.isOwnerOnly()).isTrue();
        assertThat(command.getRequiredPermission())
            .isEqualTo(fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole.VIEWERS);
    }
}
