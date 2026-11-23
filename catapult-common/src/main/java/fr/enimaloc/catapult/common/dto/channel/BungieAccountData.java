package fr.enimaloc.catapult.common.dto.channel;

import java.time.Instant;

/**
 * The owner's Bungie.net link, for the settings card: whether it is linked, the Bungie Name ({@code account},
 * {@code Name#1234}), the platform their Destiny 2 profile is read from ({@code steam}, {@code xbox}, {@code psn},
 * {@code epic}…, null when the account has no Destiny 2 profile), whether Bungie refused the link since
 * ({@code invalid}: link again), when it was linked, and whether linking is offered on this instance
 * ({@code available}: a Bungie application is set). Never a token, never an internal id.
 */
public record BungieAccountData(boolean linked, String account, String platform, boolean invalid, boolean available,
                                Instant linkedAt) {

    /** Not linked. */
    public static BungieAccountData unlinked(boolean available) {
        return new BungieAccountData(false, null, null, false, available, null);
    }
}
