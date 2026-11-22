package fr.enimaloc.catapult.common.dto.channel;

/** A member's personal RetroAchievements web API key, in a request body only; {@link #toString()} never shows it. */
public record RetroAchievementsApiKeyRequest(String apiKey) {

    @Override
    public String toString() {
        return "RetroAchievementsApiKeyRequest[apiKey=" + (apiKey == null ? "null" : "***") + "]";
    }
}
