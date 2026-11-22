package fr.enimaloc.catapult.event.blizzard;

import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The owner's Blizzard settings as they now stand: whether Battle.net is linked, the region of the account (null for
 * none), whether the World of Warcraft characters were imported, whether there are any to pick the main from, the
 * main's key ({@code <region>:<id>}, null for none), the day they were imported ({@code yyyy-MM-dd}, UTC; empty for
 * never) and whether the import is offered on this instance. Names only, never a token.
 */
public record BlizzardAccountChangedEvent(String username, boolean linked, String region, boolean wowImported,
                                          boolean hasCharacters, String wowMainKey, String importedOn,
                                          boolean available) implements ChannelUpdatedEvent {

    public static BlizzardAccountChangedEvent of(String username, BlizzardStatsData data) {
        return new BlizzardAccountChangedEvent(username, data.linked(), data.region(), data.wowImported(),
                !data.characters().isEmpty(), data.wowMainKey(), importedOn(data.wowImportedAt()), data.available());
    }

    /** The day of {@code at} as {@code yyyy-MM-dd} (UTC), empty for none. */
    public static String importedOn(Instant at) {
        return at == null ? "" : LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }
}
