package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class StreamOnlineEvent extends ApplicationEvent {

    private final UserAccount user;

    public StreamOnlineEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
