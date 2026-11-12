package fr.enimaloc.catapult.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The learned rate limits ({@code rate-limit.learning.*}): the hosts left to their documented limits
 * ({@code *.example.com} for every subdomain), the share of a limit bulk work may use, how long a live call may wait,
 * how long without a 429 before a limit is raised, and before a failed resume delay is forgotten.
 */
@Component
@ConfigurationProperties(prefix = "rate-limit.learning")
@Getter
@Setter
public class LearnedRateLimitProperties {

    private boolean enabled = true;
    private List<String> excludedHosts = new ArrayList<>(List.of("api.steampowered.com", "api.twitch.tv",
            "id.twitch.tv", "api.igdb.com", "www.doesthedogdie.com", "git.enimaloc.fr", "oauth.battle.net",
            "*.battle.net", "*.api.blizzard.com"));
    private double bulkMargin = 0.8;
    private Duration liveMaxWait = Duration.ofSeconds(2);
    private Duration raiseAfter = Duration.ofHours(1);
    private Duration forgetFailAfter = Duration.ofHours(24);
    /** Past this many routes, a host's new ones share a single route ({@code /{other}}): what is stored stays bounded. */
    private int maxRoutesPerHost = 50;
}
