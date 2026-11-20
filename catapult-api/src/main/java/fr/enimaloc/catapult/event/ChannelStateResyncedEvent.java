package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * The channel's category as read again when its EventSub connection opens (Helix, or the simulated data standing in
 * for it). Not a change: a {@code channel.update} made while the socket was down is never replayed, so this lets the
 * timeline catch up, without the notifications a {@link ChannelCategoryChangedEvent} sets off.
 */
@Getter
public class ChannelStateResyncedEvent extends ApplicationEvent {

    private final transient UserAccount user;
    private final String categoryId;
    private final String categoryName;

    public ChannelStateResyncedEvent(Object source, UserAccount user, String categoryId, String categoryName) {
        super(source);
        this.user = user;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
    }
}
