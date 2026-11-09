package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A real Twitch user read by login, for the control panel to prefill a fictitious account with: Helix
 * {@code /users} with the app token. Under the simulation that token is the {@code twitch.app.app-token} call's
 * (real by default); a simulated one makes Twitch answer 401, which surfaces as the lookup failing.
 */
@Component
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
@DependsOn("mockGuard")
public class TwitchUserLookup {

    private static final String HELIX_USERS_URL = "https://api.twitch.tv/helix/users";

    private final RestClient restClient;
    private final TwitchTokenService tokens;
    private final String clientId;

    public TwitchUserLookup(RestClient restClient, TwitchTokenService tokens,
                            @Value("${twitch.client-id:}") String clientId) {
        this.restClient = restClient;
        this.tokens = tokens;
        this.clientId = clientId;
    }

    /** What a fictitious account copies from a real Twitch user. */
    public record TwitchUser(String id, String login, @Nullable String profileImageUrl) {}

    /**
     * The Twitch user {@code login}, empty when there is none.
     *
     * @throws IllegalArgumentException for a blank login
     * @throws IllegalStateException    when Twitch cannot be asked or refuses
     */
    @SuppressWarnings("unchecked")
    public Optional<TwitchUser> byLogin(String login) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("A Twitch login is needed");
        }
        String normalized = login.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri(HELIX_USERS_URL + "?login=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8))
                    .header("Authorization", "Bearer " + tokens.getAppAccessToken())
                    .header("Client-Id", clientId)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Twitch lookup failed: " + e.getMessage(), e);
        }
        List<Map<String, Object>> data = response == null ? null : (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> user = data.getFirst();
        return Optional.of(new TwitchUser((String) user.get("id"), (String) user.get("login"),
                (String) user.get("profile_image_url")));
    }
}
