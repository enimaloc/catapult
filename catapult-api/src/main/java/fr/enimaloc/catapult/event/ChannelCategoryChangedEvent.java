package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ChannelCategoryChangedEvent extends ApplicationEvent {

    private final transient UserAccount user;
    private final String categoryId;
    private final String categoryName;

    public ChannelCategoryChangedEvent(Object source, UserAccount user, String categoryId, String categoryName) {
        super(source);
        this.user = user;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
    }
}
