package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.getter.DetectedGame;
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
