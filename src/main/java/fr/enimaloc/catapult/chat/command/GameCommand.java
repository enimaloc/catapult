package fr.esportline.catapult.chat.command;

import fr.esportline.catapult.chat.ChatCommand;
import fr.esportline.catapult.chat.ChatCommandEvent;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.service.GameStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * !game — Affiche dans le chat le jeu actuellement détecté.
 * Permission par défaut : EVERYONE
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameCommand implements ChatCommand {

    private final GameStateService gameStateService;

    @Override
    public String getName() {
        return "!game";
    }

    @Override
    public ChatCommandEvent.SenderRole getRequiredPermission() {
        return ChatCommandEvent.SenderRole.EVERYONE;
    }

    @Override
    public void execute(UserAccount user, List<String> args) {
        gameStateService.getLastKnownGame(user).ifPresentOrElse(
            game -> log.info("[!game] User {} is playing: {}", user.getTwitchUsername(), game.getSourceName()),
            () -> log.info("[!game] User {} is not playing anything", user.getTwitchUsername())
        );
        // En production : envoyer un message via EventSub/IRC au chat Twitch
    }
}
