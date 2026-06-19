package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class DynamicChatCommand implements ChatCommand {

    private final ChatCommandDefinition definition;
    private final PlaceholderResolver resolver;
    private final GameContextService gameContextService;
    private final Locale streamerLocale;

    @Override
    public String getName() {
        return definition.getName();
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return definition.getPermission();
    }

    @Override
    public Object execute(UserAccount user, List<String> args) {
        if (!definition.isEnabled()) return null;
        GameContext ctx = gameContextService.get(user).orElse(GameContext.empty());
        Map<String, String> fallbacks = definition.getFallbacks().stream()
            .collect(Collectors.toMap(
                fb -> fb.getPlaceholder(),
                fb -> fb.getFallbackText()
            ));
        Optional<String> result = resolver.resolve(definition.getTemplate(), ctx, fallbacks, streamerLocale);
        return result.orElse(null);
    }
}
