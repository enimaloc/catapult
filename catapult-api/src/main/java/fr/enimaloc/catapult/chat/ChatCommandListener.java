package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Receives the ChatCommandEvents and hands their execution to the CommandRegistry.
 * The experiment gate controls access to the dynamic (data-driven) commands.
 * <p>
 * {@link ExperimentService#evaluateGate} is used so that the first dispatch creates the
 * assignment from any admin override (and so that a LazyInitializationException is avoided
 * by staying within the service's transaction).
 */
@Component
@RequiredArgsConstructor
public class ChatCommandListener {

    public static final String EXPERIMENT_KEY = "chat.commands";

    private final CommandRegistry commandRegistry;
    private final ExperimentService experimentService;

    @EventListener
    public void onChatCommand(ChatCommandEvent event) {
        // Also reached from the IRC transport's reader thread, which has no subject of its own
        DebugContext.runAs(event.getUser().getId(), () -> {
            boolean dynamicAllowed = experimentService.evaluateGate(event.getUser(), EXPERIMENT_KEY);
            commandRegistry.dispatch(event, dynamicAllowed);
        });
    }
}
