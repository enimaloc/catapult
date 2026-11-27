package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/** The user gave their Guest Star consent: their channel's EventSub subscriptions are to be made again. */
@Getter
public class GuestStarConsentGrantedEvent extends ApplicationEvent {

    private final transient UserAccount user;

    public GuestStarConsentGrantedEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
