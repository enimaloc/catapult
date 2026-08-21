package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.IgdbClient;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import proto.ExternalGame;
import proto.Franchise;
import proto.Game;
import proto.GameMode;
import proto.Genre;
import proto.InvolvedCompany;
import proto.Keyword;
import proto.Platform;
import proto.PlayerPerspective;
import proto.Screenshot;
import proto.Theme;
import proto.Website;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class DefaultChatCommandServiceGateway implements ChatCommandServiceGateway {

    private final IgdbClient igdbClient;
    private final IgdbService igdbService;
    private final RestClient restClient;
    private final ExternalApiObservations apiObservations;
    private final SteamStoreService steamStoreService;

    @Override
    public Optional<IgdbGame> igdbGame(String query) {
        try {
            String token = igdbService.getAppToken();
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            List<Game> results = igdbClient.searchByName(query, token);
            if (results.isEmpty()) {
                return Optional.empty();
            }
            // The search endpoint only returns id/name — fetch the enriched fields (summary,
            // release date, ratings, platforms) the same way the game-details cache does.
            Game game = igdbClient.fetchGameDetails(String.valueOf(results.get(0).getId()), token)
                .orElse(results.get(0));
            return Optional.of(toIgdbGame(game));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static IgdbGame toIgdbGame(Game game) {
        String releaseDate = game.hasFirstReleaseDate() && game.getFirstReleaseDate().getSeconds() > 0
            ? DateTimeFormatter.ISO_LOCAL_DATE.format(
                Instant.ofEpochSecond(game.getFirstReleaseDate().getSeconds()).atZone(ZoneOffset.UTC))
            : "";
        String platforms = String.join(", ", mapValues(game.getPlatformsList(), Platform::getName));
        String igdbUrl = game.getSlug() == null || game.getSlug().isBlank()
            ? "" : "https://www.igdb.com/games/" + game.getSlug();
        return new IgdbGame(
            String.valueOf(game.getId()),
            game.getName() != null ? game.getName() : "",
            game.getSummary() != null ? game.getSummary() : "",
            releaseDate,
            game.getRating() > 0 ? String.valueOf(Math.round(game.getRating())) : "",
            game.getAggregatedRating() > 0 ? String.valueOf(Math.round(game.getAggregatedRating())) : "",
            platforms,
            igdbUrl
        );
    }

    @Override
    public Optional<Map<String, String>> igdbExternalPlatforms(String igdbId) {
        return fetchDetails(igdbId).map(DefaultChatCommandServiceGateway::extractExternalPlatforms);
    }

    @Override
    public Optional<List<String>> igdbGenres(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getGenresList(), Genre::getName));
    }

    @Override
    public Optional<Map<String, Object>> igdbCover(String igdbId) {
        return fetchDetails(igdbId).map(game -> {
            if (!game.hasCover()) {
                return Map.of();
            }
            proto.Cover cover = game.getCover();
            return Map.<String, Object>of("url", cover.getUrl(), "width", cover.getWidth(), "height", cover.getHeight());
        });
    }

    @Override
    public Optional<List<String>> igdbScreenshots(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getScreenshotsList(), Screenshot::getUrl));
    }

    @Override
    public Optional<List<Map<String, Object>>> igdbVideos(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getVideosList(), v -> Map.<String, Object>of(
            "name", v.getName(),
            "url", "https://www.youtube.com/watch?v=" + v.getVideoId()
        )));
    }

    @Override
    public Optional<List<String>> igdbGameModes(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getGameModesList(), GameMode::getName));
    }

    @Override
    public Optional<List<String>> igdbThemes(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getThemesList(), Theme::getName));
    }

    @Override
    public Optional<List<String>> igdbPlayerPerspectives(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getPlayerPerspectivesList(), PlayerPerspective::getName));
    }

    @Override
    public Optional<List<Map<String, Object>>> igdbInvolvedCompanies(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getInvolvedCompaniesList(),
            DefaultChatCommandServiceGateway::describeInvolvedCompany));
    }

    @Override
    public Optional<List<Map<String, Object>>> igdbAgeRatings(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getAgeRatingsList(), ar -> Map.<String, Object>of(
            "organization", ar.getOrganization().getName(),
            "rating", ar.getRating().name()
        )));
    }

    @Override
    public Optional<List<String>> igdbFranchises(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getFranchisesList(), Franchise::getName));
    }

    @Override
    public Optional<List<String>> igdbKeywords(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getKeywordsList(), Keyword::getName));
    }

    @Override
    public Optional<List<String>> igdbSimilarGames(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getSimilarGamesList(), Game::getName));
    }

    @Override
    public Optional<List<String>> igdbDlcs(String igdbId) {
        return fetchDetails(igdbId).map(game -> mapValues(game.getDlcsList(), Game::getName));
    }

    private static Map<String, Object> describeInvolvedCompany(InvolvedCompany involvedCompany) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", involvedCompany.getCompany().getName());
        map.put("developer", involvedCompany.getDeveloper());
        map.put("publisher", involvedCompany.getPublisher());
        map.put("supporting", involvedCompany.getSupporting());
        map.put("porting", involvedCompany.getPorting());
        return map;
    }

    private static <T, R> List<R> mapValues(List<T> items, Function<T, R> mapper) {
        return items.stream().map(mapper).toList();
    }

    private Optional<Game> fetchDetails(String igdbId) {
        try {
            String token = igdbService.getAppToken();
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            return igdbClient.fetchGameDetails(igdbId, token);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // Merges websites (official site, wikipedia, ...) and external_games (Steam/Xbox/... store
    // links) into one lowercased-source -> id/url map — mirrors
    // fr.enimaloc.catapult.service.IgdbGameDetailsService#extractWebsites; not extracted to a
    // shared helper since that one persists to a JPA entity and this one feeds the sandbox.
    private static Map<String, String> extractExternalPlatforms(Game game) {
        Map<String, String> map = new HashMap<>();
        for (Website website : game.getWebsitesList()) {
            String category = website.getCategory().name().toLowerCase(Locale.ROOT);
            if (category.startsWith("website_")) {
                category = category.substring("website_".length());
            }
            if (website.getUrl() != null && !website.getUrl().isBlank()) {
                map.put(category, website.getUrl());
            }
        }
        for (ExternalGame external : game.getExternalGamesList()) {
            if (!external.hasExternalGameSource()) {
                continue;
            }
            String source = external.getExternalGameSource().getName();
            if (source == null || source.isBlank()) {
                continue;
            }
            if (external.getUid() != null && !external.getUid().isBlank()) {
                map.put(source.toLowerCase(Locale.ROOT), external.getUid());
            }
        }
        return map;
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
                String effectiveAppId = steamStoreService.resolveEffectiveApp(appId)
                        .map(SteamStoreService.ResolvedParentApp::appId)
                        .orElse(appId);
                // Steam's appdetails language parameter is "l" (e.g. l=french), not "locale" —
                // defaulting to "english" both documents the fallback and keeps a single URI
                // template (no branching on whether locale was supplied).
                String lang = (locale != null && !locale.isBlank()) ? locale : "english";
                Map<?, ?> body = restClient.get()
                        .uri("https://store.steampowered.com/api/appdetails?appids={appId}&l={lang}", effectiveAppId, lang)
                        .retrieve()
                        .body(Map.class);
                if (body == null) return Optional.empty();
                // Steam appdetails unwrapping (body.get(appId) -> success -> data -> field) mirrors
                // fr.enimaloc.catapult.service.SteamStoreServiceImpl; not extracted to a shared helper here.
                Map<?, ?> appEntry = (Map<?, ?>) body.get(effectiveAppId);
                if (appEntry == null || !Boolean.TRUE.equals(appEntry.get("success"))) return Optional.empty();
                Map<?, ?> data = (Map<?, ?>) appEntry.get("data");
                return Optional.ofNullable(data);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
