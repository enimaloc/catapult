package fr.enimaloc.catapult.service.enrichment.bungie;

/**
 * The ways a Bungie.net call fails. No message ever carries a token, a key nor a response body.
 */
public final class BungieExceptions {

    private BungieExceptions() {
    }

    /** Bungie confirmed that the Destiny 2 account does not exist ({@code DestinyAccountNotFound}). */
    public static class NotFound extends RuntimeException {
        public NotFound() {
            super("Bungie does not know this Destiny 2 account");
        }
    }

    /** Bungie refused the data for the player's privacy settings ({@code DestinyPrivacyRestriction}). */
    public static class Private extends RuntimeException {
        public Private() {
            super("the Destiny 2 profile is not public");
        }
    }

    /** Bungie refused the member's access token (expired or revoked): renew it and try once more. */
    public static class AuthRefused extends RuntimeException {
        public AuthRefused() {
            super("Bungie refused the access token");
        }
    }

    /** Bungie refused the refresh token ({@code invalid_grant}): the link has to be made again. */
    public static class RefreshRefused extends RuntimeException {
        public RefreshRefused() {
            super("Bungie refused the refresh token");
        }
    }
}
