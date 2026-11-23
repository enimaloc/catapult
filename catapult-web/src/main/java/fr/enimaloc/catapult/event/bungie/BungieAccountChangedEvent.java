package fr.enimaloc.catapult.event.bungie;

import fr.enimaloc.catapult.common.dto.channel.BungieAccountData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's Bungie.net link as it now stands: whether it is linked and usable, the Bungie Name ({@code account},
 * empty for none), whether Bungie refused it since ({@code invalid}), whether the account has a Destiny 2 profile,
 * whether linking is offered on this instance and whether the link button shows ({@code canLink}: offered, and
 * nothing usable is linked). Names only, never a token.
 */
public record BungieAccountChangedEvent(String username, boolean linked, boolean usable, String account,
                                        boolean invalid, boolean noProfile, boolean available, boolean canLink)
        implements ChannelUpdatedEvent {

    public static BungieAccountChangedEvent of(String username, BungieAccountData data) {
        boolean usable = data.linked() && !data.invalid();
        return new BungieAccountChangedEvent(username, data.linked(), usable,
                data.account() == null ? "" : data.account(), data.linked() && data.invalid(),
                usable && data.platform() == null, data.available(), data.available() && !usable);
    }
}
