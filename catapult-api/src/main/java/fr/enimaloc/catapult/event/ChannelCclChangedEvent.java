package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

@Getter
public class ChannelCclChangedEvent extends ApplicationEvent {

    private final transient UserAccount user;
    private final List<String> cclIds;

    public ChannelCclChangedEvent(Object source, UserAccount user, List<String> cclIds) {
        super(source);
        this.user = user;
        this.cclIds = List.copyOf(cclIds);
    }
}
