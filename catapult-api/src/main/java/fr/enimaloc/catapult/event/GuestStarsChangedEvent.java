package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/** The Guest Star guests on air in the user's channel changed (their names, as the title shows them). */
@Getter
public class GuestStarsChangedEvent extends ApplicationEvent {

    private final transient UserAccount user;

    public GuestStarsChangedEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
