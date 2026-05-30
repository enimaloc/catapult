package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Publié lorsqu'un jeu est détecté et qu'il diffère du dernier jeu connu pour cet utilisateur.
 */
@Getter
public class GameDetectedEvent extends ApplicationEvent {

    private final UserAccount user;
    private final DetectedGame detectedGame;

    public GameDetectedEvent(Object source, UserAccount user, DetectedGame detectedGame) {
        super(source);
        this.user = user;
        this.detectedGame = detectedGame;
    }
}
