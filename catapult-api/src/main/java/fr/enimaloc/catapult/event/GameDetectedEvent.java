package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Publié lorsqu'un jeu est détecté et qu'il diffère du dernier jeu connu pour cet utilisateur.
 * {@code firstSinceStart} : première observation de l'utilisateur depuis le démarrage de catapult-api — l'état
 * précédent est inconnu (perdu au redémarrage), le jeu n'est donc pas forcément nouveau.
 */
@Getter
public class GameDetectedEvent extends ApplicationEvent {

    private final transient UserAccount user;
    private final transient DetectedGame detectedGame;
    private final boolean firstSinceStart;

    public GameDetectedEvent(Object source, UserAccount user, DetectedGame detectedGame) {
        this(source, user, detectedGame, false);
    }

    public GameDetectedEvent(Object source, UserAccount user, DetectedGame detectedGame, boolean firstSinceStart) {
        super(source);
        this.user = user;
        this.detectedGame = detectedGame;
        this.firstSinceStart = firstSinceStart;
    }
}
