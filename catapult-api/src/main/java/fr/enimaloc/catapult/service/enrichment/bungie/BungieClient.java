package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.CurrentUser;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Definition;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Profile;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Season;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.TokenGrant;

/**
 * The Bungie.net API, with the application an admin set ({@code X-API-Key} on every call, the OAuth client on the
 * token's). Every call is charged to the {@link BungieRateLimiter}. Failures are {@link BungieUnavailableException};
 * only Bungie's own error codes mean {@link BungieExceptions.NotFound not found} or
 * {@link BungieExceptions.Private private}; a refused access token is {@link BungieExceptions.AuthRefused}, a refused
 * refresh token {@link BungieExceptions.RefreshRefused}.
 */
public interface BungieClient {

    /** A new access and refresh token for {@code refreshToken} (which Bungie then retires). */
    TokenGrant refresh(String refreshToken);

    /** The Bungie.net account {@code accessToken} belongs to, with its Destiny memberships. */
    CurrentUser currentUser(String accessToken);

    /** The profile, characters and current activities of a Destiny 2 membership, read as its owner. */
    Profile profile(int membershipType, String membershipId, String accessToken);

    /** The active triumph score of a Destiny 2 membership, read as its owner; null when Bungie withholds it. */
    Integer triumphScore(int membershipType, String membershipId, String accessToken);

    /** The name and icon of the manifest definition {@code hash} of {@code entity} ({@code DestinyActivityDefinition}…). */
    Definition definition(String entity, long hash, String locale);

    /** The hash of the season in progress (Bungie's settings). */
    long currentSeasonHash();

    /** The season definition {@code hash}. */
    Season season(long hash, String locale);
}
