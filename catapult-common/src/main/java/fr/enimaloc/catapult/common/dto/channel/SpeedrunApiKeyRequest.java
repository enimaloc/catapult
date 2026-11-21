package fr.enimaloc.catapult.common.dto.channel;

/** A member's personal speedrun.com API key, in a request body only; {@link #toString()} never shows it. */
public record SpeedrunApiKeyRequest(String apiKey) {

    @Override
    public String toString() {
        return "SpeedrunApiKeyRequest[apiKey=" + (apiKey == null ? "null" : "***") + "]";
    }
}
