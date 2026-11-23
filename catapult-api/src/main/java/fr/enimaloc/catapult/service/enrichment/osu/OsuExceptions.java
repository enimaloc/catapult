package fr.enimaloc.catapult.service.enrichment.osu;

/**
 * The ways an osu! call fails that are not an outage. No message ever carries a token, a secret nor a response body.
 */
public final class OsuExceptions {

    private OsuExceptions() {
    }

    /** osu! refused the member's access token (expired or revoked, a 401): renew it and try once more. */
    public static class AuthRefused extends RuntimeException {
        public AuthRefused() {
            super("osu! refused the access token");
        }
    }

    /** osu! refused the refresh token ({@code invalid_grant}): the link has to be made again. */
    public static class RefreshRefused extends RuntimeException {
        public RefreshRefused() {
            super("osu! refused the refresh token");
        }
    }
}
