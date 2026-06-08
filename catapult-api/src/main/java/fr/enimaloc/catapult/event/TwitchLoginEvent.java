package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class TwitchLoginEvent extends ApplicationEvent {

    private final transient UserAccount user;

    public TwitchLoginEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
