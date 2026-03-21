package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Publié lorsque tous les getters actifs retournent vide et que l'utilisateur
 * était précédemment en train de jouer.
 */
@Getter
public class NoGameDetectedEvent extends ApplicationEvent {

    private final UserAccount user;

    public NoGameDetectedEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
