package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reçoit les ChatCommandEvent et délègue l'exécution au CommandRegistry.
 * Le gate d'expérience contrôle l'accès aux commandes dynamiques (data-driven).
 */
@Component
@RequiredArgsConstructor
public class ChatCommandListener {

    public static final String EXPERIMENT_KEY = "chat.commands";

    private final CommandRegistry commandRegistry;
    private final ExperimentService experimentService;

    @EventListener
    public void onChatCommand(ChatCommandEvent event) {
        boolean dynamicAllowed = experimentService.isRolledOut(event.getUser(), EXPERIMENT_KEY);
        commandRegistry.dispatch(event, dynamicAllowed);
    }
}
