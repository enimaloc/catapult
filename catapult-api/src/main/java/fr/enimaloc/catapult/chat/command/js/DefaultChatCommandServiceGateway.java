package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.IgdbClient;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
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
    private final ExternalApiObservations apiObservations;

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
        try {
            return Optional.ofNullable(user).map(UserAccount::getTwitchUsername);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> steamPrice(String appId) {
        return apiObservations.observe("steam_store", "chat_command_get_price", () -> {
            try {
                Map<?, ?> body = restClient.get()
                    .uri("https://store.steampowered.com/api/appdetails?appids={appId}&filters=price_overview", appId)
                    .retrieve()
                    .body(Map.class);
                if (body == null) return Optional.empty();
                // Steam appdetails unwrapping (body.get(appId) -> success -> data -> field) mirrors
                // fr.enimaloc.catapult.service.SteamStoreServiceImpl; not extracted to a shared helper here.
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
        });
    }

    @Override
    public Optional<Object> steamGame(String appId, @Nullable String locale) {
        return apiObservations.observe("steam_store", "chat_command_get_game", () -> {
            try {
                // Steam's appdetails language parameter is "l" (e.g. l=french), not "locale" —
                // defaulting to "english" both documents the fallback and keeps a single URI
                // template (no branching on whether locale was supplied).
                String lang = (locale != null && !locale.isBlank()) ? locale : "english";
                Map<?, ?> body = restClient.get()
                        .uri("https://store.steampowered.com/api/appdetails?appids={appId}&l={lang}", appId, lang)
                        .retrieve()
                        .body(Map.class);
                if (body == null) return Optional.empty();
                // Steam appdetails unwrapping (body.get(appId) -> success -> data -> field) mirrors
                // fr.enimaloc.catapult.service.SteamStoreServiceImpl; not extracted to a shared helper here.
                Map<?, ?> appEntry = (Map<?, ?>) body.get(appId);
                if (appEntry == null || !Boolean.TRUE.equals(appEntry.get("success"))) return Optional.empty();
                Map<?, ?> data = (Map<?, ?>) appEntry.get("data");
                return Optional.ofNullable(data);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
