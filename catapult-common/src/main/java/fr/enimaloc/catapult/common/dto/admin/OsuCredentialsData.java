package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * The osu! OAuth application set on catapult-api, for the admin Keys tab: whether one is set, its client id (public)
 * and when it was last changed. Never the client secret.
 */
public record OsuCredentialsData(boolean configured, String clientId, Instant updatedAt) {

    /** Nothing set. */
    public static OsuCredentialsData none() {
        return new OsuCredentialsData(false, null, null);
    }
}
