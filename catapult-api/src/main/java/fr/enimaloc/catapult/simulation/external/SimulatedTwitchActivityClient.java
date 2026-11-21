package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityClient;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchClips;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchLives;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Twitch activity in the mock profile: every category exists and has 42 lives and one clip. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedTwitchActivityClient implements TwitchActivityClient {

    private static final String CATEGORY_ID = "1";

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Optional<TwitchCategory> categoryByIgdbId(String igdbId) {
        return Optional.of(new TwitchCategory(CATEGORY_ID, "Simulated game"));
    }

    @Override
    public Optional<TwitchCategory> categoryById(String id) {
        return Optional.of(new TwitchCategory(id, "Simulated game"));
    }

    @Override
    public TwitchLives lives(String gameId, String language) {
        String lang = language == null ? "en" : language;
        TwitchLives.Stream top = new TwitchLives.Stream("Simulated streamer", "simulated", "Playing live", 1200, lang,
                "https://www.twitch.tv/simulated");
        return new TwitchLives(42, 4200, false, List.of(top));
    }

    @Override
    public TwitchClips clips(String gameId, Instant since) {
        return new TwitchClips(List.of(new TwitchClips.Clip("A simulated clip", "Simulated streamer", 3400, 28.0,
                "https://clips.twitch.tv/simulated", "https://clips-media-assets2.twitch.tv/simulated.jpg")));
    }
}
