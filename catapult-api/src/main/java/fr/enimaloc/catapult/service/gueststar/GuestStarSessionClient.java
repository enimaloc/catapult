package fr.enimaloc.catapult.service.gueststar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The Guest Star guests on air, read from Helix ({@code GET /guest_star/session}, beta) with the streamer's token as
 * their own moderator, their names from {@code GET /users}. Any failure (no token, 401/403, the beta gone) is logged
 * and gives no guest: the title then shows none.
 */
@Slf4j
@Component
public class GuestStarSessionClient {

    private static final String HELIX = "https://api.twitch.tv/helix";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OAuthTokenRepository tokens;
    private final TwitchTokenService tokenService;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    public GuestStarSessionClient(RestClient restClient, ObjectMapper objectMapper, OAuthTokenRepository tokens,
                                  TwitchTokenService tokenService) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.tokenService = tokenService;
    }

    /** The guests on air in {@code user}'s session, the broadcaster's slot included (the roster leaves it out). */
    public List<GuestStarRoster.Guest> liveGuests(UserAccount user) {
        Optional<OAuthToken> token = tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH);
        if (token.isEmpty()) return List.of();
        try {
            String accessToken = tokenService.resolveAccessToken(token.get(), user);
            JsonNode session = get(accessToken, HELIX + "/guest_star/session?broadcaster_id=" + user.getTwitchId()
                    + "&moderator_id=" + user.getTwitchId()).path("data").path(0);
            List<String[]> live = new ArrayList<>();
            session.path("guests").forEach(guest -> {
                if (guest.path("is_live").asBoolean(false)) {
                    live.add(new String[]{guest.path("slot_id").asText(), guest.path("user_id").asText()});
                }
            });
            if (live.isEmpty()) return List.of();
            Map<String, String> names = new HashMap<>();
            get(accessToken, HELIX + "/users?" + live.stream().map(g -> "id=" + g[1]).collect(Collectors.joining("&")))
                    .path("data").forEach(u -> names.put(u.path("id").asText(), u.path("display_name").asText()));
            return live.stream()
                    .map(g -> new GuestStarRoster.Guest(g[0], g[1], names.getOrDefault(g[1], g[1])))
                    .toList();
        } catch (Exception e) {
            log.warn("Could not read the Guest Star session of user {}: {}", user.getId(), e.getMessage());
            return List.of();
        }
    }

    private JsonNode get(String accessToken, String url) throws Exception {
        String body = restClient.get()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-ID", twitchClientId)
                .retrieve()
                .body(String.class);
        return body == null ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }
}
