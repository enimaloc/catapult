package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * The streamer raided another channel ({@code channel.raid} with them as {@code from_broadcaster}).
 * {@code currentCategoryId} is the channel's Twitch category as last seen, null when unknown;
 * {@code fromBroadcasterName} the raider's Twitch display name as the raid carries it, null when unknown;
 * {@code toBroadcasterId} the raided channel's Twitch id, null when unknown; {@code occurredAt}
 * when the raid left (the EventSub message time, else its reception).
 */
@Getter
public class ChannelRaidedOutEvent extends ApplicationEvent {

    private final transient UserAccount user;
    private final String currentCategoryId;
    private final String fromBroadcasterName;
    private final String toBroadcasterId;
    private final Instant occurredAt;

    public ChannelRaidedOutEvent(Object source, UserAccount user, String currentCategoryId) {
        this(source, user, currentCategoryId, null, null, Instant.now());
    }

    public ChannelRaidedOutEvent(Object source, UserAccount user, String currentCategoryId, String fromBroadcasterName,
                                 String toBroadcasterId, Instant occurredAt) {
        super(source);
        this.user = user;
        this.currentCategoryId = currentCategoryId;
        this.fromBroadcasterName = fromBroadcasterName;
        this.toBroadcasterId = toBroadcasterId;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
