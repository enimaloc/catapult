package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.IgdbClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import proto.Game;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DefaultChatCommandServiceGateway implements ChatCommandServiceGateway {

    private final IgdbClient igdbClient;
    private final RestClient restClient;

    @Override
    public Optional<String> igdbGameName(String query) {
        try {
            List<Game> results = igdbClient.searchByName(query, "");
            return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0).getName());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> twitchOwnDisplayName(UserAccount user) {
        // Phase 1 scope: only the broadcaster's own profile is exposed,
        // not arbitrary Twitch user lookups (no Twitch user-info client exists yet).
        // NOTE: brief's reference used UserAccount#getDisplayName(), which does not
        // exist on this entity — the closest equivalent is the Twitch username.
        return Optional.ofNullable(user).map(UserAccount::getTwitchUsername);
    }

    @Override
    public Optional<String> steamPrice(String appId) {
        try {
            Map<?, ?> body = restClient.get()
                .uri("https://store.steampowered.com/api/appdetails?appids={appId}&filters=price_overview", appId)
                .retrieve()
                .body(Map.class);
            if (body == null) return Optional.empty();
            Map<?, ?> appEntry = (Map<?, ?>) body.get(appId);
            if (appEntry == null || !Boolean.TRUE.equals(appEntry.get("success"))) return Optional.empty();
            Map<?, ?> data = (Map<?, ?>) appEntry.get("data");
            if (data == null) return Optional.empty();
            Map<?, ?> priceOverview = (Map<?, ?>) data.get("price_overview");
            if (priceOverview == null) return Optional.empty();
            return Optional.ofNullable((String) priceOverview.get("final_formatted"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
