package fr.esportline.catapult.event;

import fr.esportline.catapult.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Captures game detection events and writes them to the per-user activity log,
 * so the dashboard live feed reflects what the scheduler is doing.
 */
@Component
@RequiredArgsConstructor
public class ActivityLogEventListener {

    private final ActivityLogService activityLogService;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        String game = event.getDetectedGame().getSourceName();
        String source = event.getDetectedGame().getSourceType().name();
        activityLogService.addEntry(
            event.getUser().getId(),
            "INFO",
            "Jeu détecté via " + source + " : " + game
        );
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        activityLogService.addEntry(
            event.getUser().getId(),
            "INFO",
            "Aucun jeu détecté (fin de session)"
        );
    }
}
