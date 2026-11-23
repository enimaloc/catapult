package fr.enimaloc.catapult.event.osu;

import fr.enimaloc.catapult.common.dto.channel.OsuAccountData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's osu! link as it now stands: whether it is linked and usable, the osu! username ({@code account}, empty
 * for none), whether osu! refused it since ({@code invalid}), whether linking is offered on this instance and whether
 * the link button shows ({@code canLink}: offered, and nothing usable is linked). Names only, never a token.
 */
public record OsuAccountChangedEvent(String username, boolean linked, boolean usable, String account,
                                     boolean invalid, boolean available, boolean canLink)
        implements ChannelUpdatedEvent {

    public static OsuAccountChangedEvent of(String username, OsuAccountData data) {
        boolean usable = data.linked() && !data.invalid();
        return new OsuAccountChangedEvent(username, data.linked(), usable,
                data.account() == null ? "" : data.account(), data.linked() && data.invalid(), data.available(),
                data.available() && !usable);
    }
}
