package fr.esportline.catapult.chat.command;

import fr.esportline.catapult.chat.ChatCommand;
import fr.esportline.catapult.chat.ChatCommandEvent;
import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * !setgame <game name> — Force manuellement une catégorie Twitch pour la session.
 * Permission par défaut : MODERATOR
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetGameCommand implements ChatCommand {

    private final TwitchService twitchService;

    @Override
    public String getName() {
        return "!setgame";
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return ChatCommandEvent.SenderRole.MODERATOR;
    }

    @Override
    public void execute(UserAccount user, List<String> args) {
        if (args.isEmpty()) {
            log.debug("[!setgame] No game name provided for user {}", user.getTwitchUsername());
            return;
        }

        String gameName = String.join(" ", args);
        log.info("[!setgame] User {} requested manual game: {}", user.getTwitchUsername(), gameName);

        // Crée un binding temporaire pour la mise à jour immédiate
        // La résolution IGDB correcte se fera au prochain cycle de polling
        GameBinding tempBinding = new GameBinding();
        tempBinding.setUser(user);
        tempBinding.setSourceType(GameBinding.SourceType.MANUAL);
        tempBinding.setSourceName(gameName);
        tempBinding.setTwitchGameName(gameName);
        tempBinding.setStatus(GameBinding.Status.MANUAL);
        tempBinding.setCcls(Set.of());

        twitchService.updateChannel(user, tempBinding);
    }
}
