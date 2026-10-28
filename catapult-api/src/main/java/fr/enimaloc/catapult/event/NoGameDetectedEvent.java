package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when all active getters return empty and the user
 * was previously playing.
 */
@Getter
public class NoGameDetectedEvent extends ApplicationEvent {

    private final transient UserAccount user;

    public NoGameDetectedEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
