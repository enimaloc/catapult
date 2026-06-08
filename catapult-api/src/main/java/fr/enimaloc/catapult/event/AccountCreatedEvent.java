package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AccountCreatedEvent extends ApplicationEvent {

    private final transient UserAccount user;

    public AccountCreatedEvent(Object source, UserAccount user) {
        super(source);
        this.user = user;
    }
}
