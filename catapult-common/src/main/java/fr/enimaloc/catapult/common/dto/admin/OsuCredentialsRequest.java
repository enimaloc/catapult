package fr.enimaloc.catapult.common.dto.admin;

/**
 * The osu! OAuth application an admin sets: its client id and client secret. A blank secret keeps the one already
 * set (same client id only). {@link #toString()} never shows the secret.
 */
public record OsuCredentialsRequest(String clientId, String clientSecret) {

    @Override
    public String toString() {
        return "OsuCredentialsRequest[clientId=" + clientId + ", clientSecret=***]";
    }
}
