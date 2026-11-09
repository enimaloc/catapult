package fr.enimaloc.catapult.simulation.account;

import org.jspecify.annotations.Nullable;

/**
 * What a fictitious account is known by, as the control panel types it or prefills it from a real Twitch user.
 *
 * @param twitchId        with or without the fictitious prefix, which is added when missing; only read at creation
 * @param profileImageUrl the avatar shown for it
 * @param steamId         its Steam id, a fictitious one made up when blank at creation
 */
public record FictitiousIdentity(@Nullable String username, @Nullable String twitchId,
                                 @Nullable String profileImageUrl, @Nullable String steamId) {

    /** Only a username, every other field made up or left empty. */
    public static FictitiousIdentity named(@Nullable String username) {
        return new FictitiousIdentity(username, null, null, null);
    }
}
