package fr.enimaloc.catapult.event.faceit;

import fr.enimaloc.catapult.common.dto.channel.FaceitData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's FACEIT settings as they now stand: the nickname they declared (empty for none), its FACEIT profile page
 * (empty for none) and whether the FACEIT stats can be served on this instance. No secret is held for a member.
 */
public record FaceitAccountChangedEvent(String username, String nickname, String profileUrl, boolean available)
        implements ChannelUpdatedEvent {

    public static FaceitAccountChangedEvent of(String username, FaceitData data) {
        return new FaceitAccountChangedEvent(username, data.nickname() == null ? "" : data.nickname(),
                data.profileUrl() == null ? "" : data.profileUrl(), data.available());
    }
}
