package fr.enimaloc.catapult.common.dto.channel;

import java.time.Instant;

/**
 * The owner's osu! link, for the settings card: whether it is linked, the osu! username ({@code account}), whether
 * osu! refused the link since ({@code invalid}: link again), when it was linked, and whether linking is offered on
 * this instance ({@code available}: an osu! application is set). Never a token, never an internal id.
 */
public record OsuAccountData(boolean linked, String account, boolean invalid, boolean available, Instant linkedAt) {

    /** Not linked. */
    public static OsuAccountData unlinked(boolean available) {
        return new OsuAccountData(false, null, false, available, null);
    }
}
