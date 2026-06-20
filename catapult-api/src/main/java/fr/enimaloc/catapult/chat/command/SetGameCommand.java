package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommand;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * !setgame &lt;game name&gt; — Force manuellement une catégorie Twitch pour la session.
 * Permission par défaut : MODERATOR.
 * <p>
 * La réponse texte est customisable via une {@link ChatCommandDefinition}
 * (préchargée par {@code ChatCommandPresetCatalog.ensureBuiltins}). Le
 * template support le placeholder {@code {args}} qui est remplacé par la liste
 * des arguments tapés ; pas d'autre placeholder résolu ici (le contexte de
 * jeu courant n'est pas encore mis à jour à l'instant du dispatch).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetGameCommand implements ChatCommand {

    static final String DEFAULT_TEMPLATE = "Jeu mis à jour : {args}";

    private final TwitchService twitchService;
    private final ChatCommandDefinitionRepository definitionRepository;

    @Override
    public String getName() {
        return "!setgame";
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return ChatCommandEvent.SenderRole.MODERATOR;
    }

    @Override
    public Object execute(UserAccount user, List<String> args) {
        // Lookup par presetKey (pas par nom) car l'utilisateur peut renommer
        // la commande tout en gardant l'action Java associée.
        Optional<ChatCommandDefinition> defOpt = definitionRepository.findByUserAndPresetKey(
            user, "builtin:" + getName().replaceFirst("^!", ""));
        if (defOpt.isPresent() && !defOpt.get().isEnabled()) {
            return null; // explicitement désactivé par le streamer
        }
        if (args.isEmpty()) {
            return "Usage : !setgame <nom du jeu>";
        }

        String gameName = String.join(" ", args);
        log.info("[!setgame] User {} requested manual game: {}", user.getTwitchUsername(), gameName);

        GameBinding tempBinding = new GameBinding();
        tempBinding.setUser(user);
        tempBinding.setSourceType(GameBinding.SourceType.MANUAL);
        tempBinding.setSourceName(gameName);
        tempBinding.setTwitchGameName(gameName);
        tempBinding.setStatus(GameBinding.Status.MANUAL);
        tempBinding.setCcls(Set.of());
        twitchService.updateChannel(user, tempBinding);

        String template = defOpt.map(ChatCommandDefinition::getTemplate).orElse(DEFAULT_TEMPLATE);
        return template.replace("{args}", gameName);
    }
}
