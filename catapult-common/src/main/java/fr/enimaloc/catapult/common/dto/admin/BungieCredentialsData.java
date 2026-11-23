package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * The Bungie.net application set on catapult-api, for the admin Keys tab: whether one is set, its OAuth client id
 * (public), its API key masked ({@code abcd…wxyz}) and when it was last changed. Never the client secret nor the raw
 * key.
 */
public record BungieCredentialsData(boolean configured, String clientId, String apiKeyMasked, Instant updatedAt) {

    /** Nothing set. */
    public static BungieCredentialsData none() {
        return new BungieCredentialsData(false, null, null, null);
    }
}
