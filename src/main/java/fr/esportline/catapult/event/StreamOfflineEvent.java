package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class StreamOfflineEvent extends ApplicationEvent {

    private final UserAccount user;

    public StreamOfflineEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
