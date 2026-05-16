package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommand;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * !game — Affiche dans le chat le jeu actuellement détecté.
 * Permission par défaut : EVERYONE
 */
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
    public Object execute(UserAccount user, List<String> args) {
        return gameStateService.getLastKnownGame(user)
            .map(game -> "Actuellement : " + game.getSourceName())
            .orElse("Aucun jeu détecté");
    }
}
