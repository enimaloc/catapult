package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ChatCommandDefinitionChangedEvent;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.service.GameContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DynamicCommandResolver {

    private final ChatCommandDefinitionRepository repository;
    private final PlaceholderResolver placeholderResolver;
    private final GameContextService gameContextService;
    private final Map<String, ChatCommand> staticByPresetKey;

    private final Map<UUID, Map<String, Optional<ChatCommand>>> userCache = new ConcurrentHashMap<>();

    @Autowired
    public DynamicCommandResolver(ChatCommandDefinitionRepository repository,
                                  PlaceholderResolver placeholderResolver,
                                  GameContextService gameContextService,
                                  List<ChatCommand> staticCommands) {
        this.repository = repository;
        this.placeholderResolver = placeholderResolver;
        this.gameContextService = gameContextService;
        this.staticByPresetKey = staticCommands.stream()
            .collect(Collectors.toMap(
                c -> ChatCommandPresetCatalog.BUILTIN_PRESET_KEY_PREFIX
                     + c.getName().replaceFirst("^!", ""),
                c -> c,
                (a, b) -> a));
    }

    public Optional<ChatCommand> resolve(UserAccount user, String name) {
        return userCache
            .computeIfAbsent(user.getId(), k -> new ConcurrentHashMap<>())
            .computeIfAbsent(name, k -> loadFromRepo(user, name));
    }

    private Optional<ChatCommand> loadFromRepo(UserAccount user, String name) {
        return repository.findByUserAndName(user, name)
            .filter(ChatCommandDefinition::isEnabled)
            .map(def -> {
                // Si la ligne est un built-in renommé, on route vers le bean
                // Java statique correspondant (sinon l'action serait perdue).
                if (ChatCommandPresetCatalog.isBuiltin(def)) {
                    ChatCommand bean = staticByPresetKey.get(def.getPresetKey());
                    if (bean != null) return bean;
                }
                return (ChatCommand) new DynamicChatCommand(
                    def, placeholderResolver, gameContextService, resolveLocale(user));
            });
    }

    private Locale resolveLocale(UserAccount user) {
        // No per-user locale is currently exposed on UserAccount/UserSettings.
        // Default to Locale.FRANCE as specified in the chat-commands plan.
        return Locale.FRANCE;
    }

    @EventListener
    public void onDefinitionChanged(ChatCommandDefinitionChangedEvent event) {
        userCache.remove(event.getUserId());
    }
}
