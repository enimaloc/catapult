package fr.enimaloc.catapult.simulation;

/**
 * A stable, readable name for one external call or incoming event, grouped by API ({@code api.call}).
 *
 * @param api   the API the call goes to, e.g. {@code twitch.helix}
 * @param call  the call or event, e.g. {@code update-channel}
 * @param scope whether its mode is set per account or once for everyone
 */
public record CallKey(String api, String call, Scope scope) {

    public enum Scope {
        /** The call acts on one account's channel: its mode is set per account. */
        ACCOUNT,
        /** The call has no account (IGDB, stores…): one mode for everyone. */
        GLOBAL
    }

    public String id() {
        return api + "." + call;
    }
}
