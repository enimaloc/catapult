package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
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
    private ChatCommandSettingRepository settingRepository;
    private DebugCommand command;
    private UserAccount user;

    @BeforeEach
    void setup() {
        twRegistry = mock(TwPlaceholderRegistry.class);
        when(twRegistry.getKnownPaths()).thenReturn(Set.of("spiders"));
        gameContextService = mock(GameContextService.class);
        settingRepository = mock(ChatCommandSettingRepository.class);
        when(settingRepository.findByUser(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        PlaceholderResolver resolver = new PlaceholderResolver(new SimpleMeterRegistry(), twRegistry);
        command = new DebugCommand(resolver, twRegistry, gameContextService,
            new JsCompiler(new ServiceFunctionRegistry()), new SandboxExecutor(),
            new ServiceFunctionRegistry(), settingRepository);

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

    @Test
    void tpl_mode_parses_compiles_and_executes_an_ad_hoc_template() {
        when(gameContextService.get(user)).thenReturn(Optional.of(contextWithGame()));

        Object result = command.execute(user, List.of("tpl", "Playing", "{game#name}", "right", "now"));

        assertThat(result).isEqualTo("Playing Celeste right now");
    }

    @Test
    void tpl_mode_reports_a_parse_error_instead_of_throwing() {
        Object result = command.execute(user, List.of("tpl", "{if", "unterminated"));

        assertThat(result).asString().startsWith("Parse error:");
    }

    @Test
    void tpl_mode_with_no_text_returns_usage() {
        Object result = command.execute(user, List.of("tpl"));

        assertThat(result).isEqualTo("Usage: !debug tpl <template text>");
    }

    @Test
    void js_mode_evaluates_raw_js_with_full_host_access() {
        Object result = command.execute(user, List.of("js", "1", "+", "1"));

        assertThat(result).isEqualTo("2");
    }

    @Test
    void js_mode_can_reach_arbitrary_host_classes_unlike_the_sandbox() {
        Object result = command.execute(user,
            List.of("js", "Java.type('java.lang.System').getProperty('java.vm.name')"));

        assertThat(result).asString().isNotBlank();
    }

    @Test
    void js_mode_reports_a_js_error_instead_of_throwing() {
        Object result = command.execute(user, List.of("js", "not(valid", "js"));

        assertThat(result).asString().startsWith("JS error:");
    }

    @Test
    void js_mode_with_no_code_returns_usage() {
        Object result = command.execute(user, List.of("js"));

        assertThat(result).isEqualTo("Usage: !debug js <code>");
    }
}
