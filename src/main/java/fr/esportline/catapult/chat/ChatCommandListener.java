package fr.esportline.catapult.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reçoit les ChatCommandEvent et délègue l'exécution au CommandRegistry.
 */
@Component
@RequiredArgsConstructor
public class ChatCommandListener {

    private final CommandRegistry commandRegistry;

    @EventListener
    public void onChatCommand(ChatCommandEvent event) {
        commandRegistry.dispatch(event);
    }
}
