package fr.enimaloc.catapult.common.dto.admin;

/**
 * The Bungie.net application an admin sets: its OAuth client id, client secret and API key. A blank secret or key
 * keeps the one already set. {@link #toString()} never shows the secrets.
 */
public record BungieCredentialsRequest(String clientId, String clientSecret, String apiKey) {

    @Override
    public String toString() {
        return "BungieCredentialsRequest[clientId=" + clientId + ", clientSecret=***, apiKey=***]";
    }
}
