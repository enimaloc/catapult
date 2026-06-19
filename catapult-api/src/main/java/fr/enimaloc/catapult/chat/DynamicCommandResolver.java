package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ChatCommandDefinitionChangedEvent;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DynamicCommandResolver {

    private final ChatCommandDefinitionRepository repository;
    private final PlaceholderResolver placeholderResolver;
    private final GameContextService gameContextService;

    private final Map<UUID, Map<String, Optional<ChatCommand>>> userCache = new ConcurrentHashMap<>();

    public Optional<ChatCommand> resolve(UserAccount user, String name) {
        return userCache
            .computeIfAbsent(user.getId(), k -> new ConcurrentHashMap<>())
            .computeIfAbsent(name, k -> loadFromRepo(user, name));
    }

    private Optional<ChatCommand> loadFromRepo(UserAccount user, String name) {
        return repository.findByUserAndName(user, name)
            .filter(ChatCommandDefinition::isEnabled)
            .map(def -> new DynamicChatCommand(def, placeholderResolver, gameContextService, resolveLocale(user)));
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
