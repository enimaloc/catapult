package fr.enimaloc.catapult.event.battlenet;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** The channel's Battle.net link as it now stands; {@code NONE} once gone, the tags then being null. */
public record BattleNetLinkEvent(String username, String status, String battleTag, String serviceAccountBattleTag)
        implements ChannelUpdatedEvent {
}
