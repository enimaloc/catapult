package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * The streamer raided another channel ({@code channel.raid} with them as {@code from_broadcaster}).
 * {@code currentCategoryId} is the channel's Twitch category as last seen, null when unknown.
 */
@Getter
public class ChannelRaidedOutEvent extends ApplicationEvent {

    private final transient UserAccount user;
    private final String currentCategoryId;

    public ChannelRaidedOutEvent(Object source, UserAccount user, String currentCategoryId) {
        super(source);
        this.user = user;
        this.currentCategoryId = currentCategoryId;
    }
}
