package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
import fr.enimaloc.catapult.event.ChatCommandDefinitionChangedEvent;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandSettingRepository;
import fr.enimaloc.catapult.service.binding.GameContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamicCommandResolverTest {

    @Mock ChatCommandDefinitionRepository repository;
    @Mock GameContextService gameContextService;

    private DynamicCommandResolver resolver;
    private UserAccount user;

    @BeforeEach
    void setup() {
        TwPlaceholderRegistry twRegistry = org.mockito.Mockito.mock(TwPlaceholderRegistry.class);
        org.mockito.Mockito.when(twRegistry.getKnownPaths()).thenReturn(java.util.Set.of());
        PlaceholderResolver placeholderResolver = new PlaceholderResolver(new SimpleMeterRegistry(), twRegistry);
        resolver = new DynamicCommandResolver(repository, placeholderResolver, gameContextService,
            new JsCompiler(), new SandboxExecutor(), new ServiceFunctionRegistry(),
            org.mockito.Mockito.mock(ChatCommandSettingRepository.class),
            new SimpleMeterRegistry(),
            java.util.List.of());
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void resolves_enabled_definition_to_dynamic_command() {
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setName("!game");
        def.setTemplate("...");
        def.setEnabled(true);
        def.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        when(repository.findByUserAndName(user, "!game")).thenReturn(Optional.of(def));

        Optional<ChatCommand> result = resolver.resolve(user, "!game");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("!game");
    }

    @Test
    void disabled_definition_returns_empty() {
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setName("!game");
        def.setEnabled(false);
        when(repository.findByUserAndName(user, "!game")).thenReturn(Optional.of(def));

        assertThat(resolver.resolve(user, "!game")).isEmpty();
    }

    @Test
    void missing_definition_returns_empty() {
        when(repository.findByUserAndName(any(), any())).thenReturn(Optional.empty());
        assertThat(resolver.resolve(user, "!foo")).isEmpty();
    }

    @Test
    void cached_after_first_resolve_until_event_invalidates() {
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setName("!game");
        def.setTemplate("...");
        def.setEnabled(true);
        def.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        when(repository.findByUserAndName(user, "!game")).thenReturn(Optional.of(def));

        resolver.resolve(user, "!game"); // populate cache
        resolver.resolve(user, "!game"); // served from cache
        verify(repository, times(1)).findByUserAndName(user, "!game");

        resolver.onDefinitionChanged(new ChatCommandDefinitionChangedEvent(this, user.getId()));

        resolver.resolve(user, "!game"); // cache miss after invalidation
        verify(repository, times(2)).findByUserAndName(user, "!game");
    }

    /** A function standing in for a real one, recording whether it ran. */
    private static fr.enimaloc.catapult.chat.command.registry.ServiceFunction function(
            String name, boolean action, java.util.concurrent.atomic.AtomicBoolean ran) {
        return new fr.enimaloc.catapult.chat.command.registry.ServiceFunction() {
            public String namespace() { return "test"; }
            public String name() { return name; }
            public java.util.List<String> parameterNames() { return java.util.List.of(); }
            public boolean isAction() { return action; }
            public Object invoke(UserAccount user, Object[] args) {
                ran.set(true);
                return action ? "" : "read";
            }
        };
    }

    private Optional<String> renderReadOnly(String template, ServiceFunctionRegistry registry) {
        TwPlaceholderRegistry twRegistry = org.mockito.Mockito.mock(TwPlaceholderRegistry.class);
        when(twRegistry.getKnownPaths()).thenReturn(java.util.Set.of());
        DynamicCommandResolver withFunctions = new DynamicCommandResolver(repository,
            new PlaceholderResolver(new SimpleMeterRegistry(), twRegistry), gameContextService,
            new JsCompiler(), new SandboxExecutor(), registry,
            org.mockito.Mockito.mock(ChatCommandSettingRepository.class), new SimpleMeterRegistry(),
            java.util.List.of());
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setName("!cmd");
        def.setTemplate(template);
        def.setEnabled(true);
        def.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        when(repository.findByUserAndName(user, "!cmd")).thenReturn(Optional.of(def));
        return withFunctions.renderReadOnly(user, "!cmd", java.util.List.of());
    }

    @Test
    void renderReadOnly_runsTheFunctionsThatRead() {
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(function("look", false, ran));

        assertThat(renderReadOnly("got {print test#look()}", registry)).contains("got read");
        assertThat(ran).isTrue();
    }

    /** Whoever holds a bot's URL must not ban, post or change the game through it. */
    @Test
    void renderReadOnly_neverRunsAnAction() {
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(function("act", true, ran));

        assertThat(renderReadOnly("{test#act()}done", registry)).isEmpty();
        assertThat(ran).isFalse();
        assertThat(registry.lookup("test", "act")).isPresent();
    }

    @Test
    void renderReadOnly_disabledCommand_answersNothing() {
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setName("!game");
        def.setTemplate("hi");
        def.setEnabled(false);
        when(repository.findByUserAndName(user, "!game")).thenReturn(Optional.of(def));

        assertThat(resolver.renderReadOnly(user, "!game", java.util.List.of())).isEmpty();
    }
}
