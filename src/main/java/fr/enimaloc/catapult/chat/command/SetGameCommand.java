package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommand;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchService;
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
    public Object execute(UserAccount user, List<String> args) {
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
        return "Jeu mis à jour : " + gameName;
    }
}
