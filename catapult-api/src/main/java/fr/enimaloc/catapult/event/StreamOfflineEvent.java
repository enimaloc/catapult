package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class StreamOfflineEvent extends ApplicationEvent {

    private final transient UserAccount user;

    public StreamOfflineEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
