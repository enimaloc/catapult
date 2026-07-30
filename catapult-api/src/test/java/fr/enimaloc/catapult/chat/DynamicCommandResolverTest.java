package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ChatCommandDefinitionChangedEvent;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.service.GameContextService;
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

    private PlaceholderResolver placeholderResolver;
    private DynamicCommandResolver resolver;
    private UserAccount user;

    @BeforeEach
    void setup() {
        TwPlaceholderRegistry twRegistry = org.mockito.Mockito.mock(TwPlaceholderRegistry.class);
        org.mockito.Mockito.when(twRegistry.getKnownPaths()).thenReturn(java.util.Set.of());
        placeholderResolver = new PlaceholderResolver(new SimpleMeterRegistry(), twRegistry);
        resolver = new DynamicCommandResolver(repository, placeholderResolver, gameContextService,
            new JsCompiler(), new SandboxExecutor(), new ServiceFunctionRegistry(),
            org.mockito.Mockito.mock(ChatCommandSettingRepository.class),
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
}
