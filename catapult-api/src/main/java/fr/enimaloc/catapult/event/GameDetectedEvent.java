package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a game is detected and differs from the last game known for this user.
 * {@code firstSinceStart}: the user's first observation since catapult-api started — the previous state
 * is unknown (lost on restart), so the game is not necessarily new.
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
