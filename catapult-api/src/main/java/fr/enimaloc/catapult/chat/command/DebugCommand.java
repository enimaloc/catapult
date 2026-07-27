package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommand;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * !debug [path] — Affiche la valeur résolue de tous les placeholders connus
 * (ou d'un seul si précisé) contre le GameContext courant du channel.
 * <p>
 * Réservée à l'owner de l'application ({@code isOwnerOnly}). Volontairement
 * absente du {@code ChatCommandPresetCatalog} et sans lookup de
 * {@code ChatCommandDefinition} : invisible dans l'UI streamer et
 * non-désactivable.
 */
@Component
@RequiredArgsConstructor
public class DebugCommand implements ChatCommand {

    static final String EMPTY_VALUE = "∅";

    private final PlaceholderResolver placeholderResolver;
    private final TwPlaceholderRegistry twPlaceholderRegistry;
    private final GameContextService gameContextService;

    @Override
    public String getName() {
        return "!debug";
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return ChatCommandEvent.SenderRole.VIEWERS;
    }

    @Override
    public boolean isOwnerOnly() {
        return true;
    }

    @Override
    public Object execute(UserAccount user, List<String> args) {
        GameContext ctx = gameContextService.get(user).orElse(GameContext.empty());
        // Même locale figée que DynamicCommandResolver (pas de locale par user).
        Locale locale = Locale.FRANCE;

        if (!args.isEmpty()) {
            String path = args.get(0);
            if (!knownPaths().contains(path)) {
                return "Placeholder inconnu : " + path;
            }
            return path + "=" + resolveOrEmpty(ctx, path, locale);
        }

        return knownPaths().stream()
            .map(path -> path + "=" + resolveOrEmpty(ctx, path, locale))
            .collect(Collectors.joining("; "));
    }

    private TreeSet<String> knownPaths() {
        TreeSet<String> paths = new TreeSet<>(PlaceholderResolver.KNOWN_PATHS);
        twPlaceholderRegistry.getKnownPaths().forEach(id -> paths.add("tw#" + id));
        return paths;
    }

    private String resolveOrEmpty(GameContext ctx, String path, Locale locale) {
        String value = placeholderResolver.lookupRaw(ctx, path, locale);
        return value == null || value.isBlank() ? EMPTY_VALUE : value;
    }
}
