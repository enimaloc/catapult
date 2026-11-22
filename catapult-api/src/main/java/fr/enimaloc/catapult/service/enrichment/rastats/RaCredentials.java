package fr.enimaloc.catapult.service.enrichment.rastats;

/** The RetroAchievements web API key a call is sent with; {@code personal} tells a member's own from the pool's. */
public record RaCredentials(String key, boolean personal) {

    @Override
    public String toString() {
        return "RaCredentials[key=***, personal=" + personal + "]";
    }
}
