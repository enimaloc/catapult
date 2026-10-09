package fr.enimaloc.catapult.service.igdb;

import com.api.igdb.apicalypse.APICalypse;
import com.api.igdb.exceptions.RequestException;
import com.api.igdb.request.IGDBWrapper;
import com.api.igdb.request.ProtoRequestKt;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import proto.AlternativeName;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Thin wrapper over the IGDB SDK's singleton client. Every call runs under a lock on that
 * singleton (its credentials are global state) and, apart from {@link #findSourcesByName},
 * turns an IGDB failure into an empty result instead of an exception.
 */
@Slf4j
@Component
public class IgdbClient {

    private static final String GAME_DETAILS_FIELDS = "id,name,slug,summary,first_release_date,websites.url,websites.type.type,external_games.uid,external_games.external_game_source.name"
            + ",rating,aggregated_rating,platforms.name,dlcs.name,similar_games.name,genres.name"
            + ",cover.url,screenshots.url,videos.video_id,game_modes.name,themes.name,player_perspectives.name"
            + ",involved_companies.company.name,involved_companies.developer,involved_companies.publisher"
            + ",involved_companies.supporting,involved_companies.porting"
            + ",age_ratings.rating_category.organization.name,age_ratings.rating_category.rating,franchises.name,keywords.name";

    @Value("${app.igdb.client-id:}")
    private String clientId;

    private String currentToken = "";

    @Autowired
    private ExternalApiObservations apiObservations;

    /** One IGDB SDK endpoint call. */
    @FunctionalInterface
    interface IgdbCall<T> {
        List<T> fetch(APICalypse query) throws RequestException;
    }

    /**
     * Résout les sources externes par nom (ex: "Steam").
     * Propage RequestException — l'appelant gère l'erreur.
     */
    public List<ExternalGameSource> findSourcesByName(String name, String token) throws RequestException {
        long start = System.nanoTime();
        try {
            APICalypse query = new APICalypse().fields("id,name").where("name = \"" + name + "\"").limit(1);
            List<ExternalGameSource> results = fetch(token, "/external_game_sources", "", query,
                    q -> ProtoRequestKt.externalGameSources(IGDBWrapper.INSTANCE, q));
            apiObservations.record("igdb", "find_sources_by_name", "success", "none", System.nanoTime() - start);
            return results;
        } catch (RequestException e) {
            apiObservations.record("igdb", "find_sources_by_name", "error", e.getClass().getSimpleName(), System.nanoTime() - start);
            throw e;
        }
    }

    /**
     * Cherche un jeu externe par son uid (Steam appId) — lookup unitaire.
     * sourceId >= 0 → filtre par external_game_source ; sinon pas de filtre source.
     */
    public List<ExternalGame> findExternalGameByUid(String uid, long sourceId, String token) {
        return apiObservations.observe("igdb", "find_external_game_by_uid", () -> {
            APICalypse query = new APICalypse().fields("uid,game.id,game.name")
                    .where(sourceFilter(sourceId) + "uid=\"" + uid + "\"").limit(1);
            return fetchOrEmpty(token, "/external_games", "uid=" + uid, query,
                    q -> ProtoRequestKt.externalGames(IGDBWrapper.INSTANCE, q));
        });
    }

    /**
     * Cherche plusieurs jeux externes par leurs uids — batch (max 500 par appel).
     * sourceId >= 0 → filtre par external_game_source ; sinon pas de filtre source.
     */
    public List<ExternalGame> findExternalGamesByUids(List<String> uids, long sourceId, String token) {
        if (uids.isEmpty()) return List.of();
        return apiObservations.observe("igdb", "find_external_games_by_uids", () -> {
            String uidList = uids.stream().map(u -> "\"" + u + "\"").collect(Collectors.joining(",", "(", ")"));
            APICalypse query = new APICalypse().fields("uid,game.id,game.name")
                    .where(sourceFilter(sourceId) + "uid=" + uidList).limit(uids.size());
            return fetchOrEmpty(token, "/external_games", "batch (" + uids.size() + ")", query,
                    q -> ProtoRequestKt.externalGames(IGDBWrapper.INSTANCE, q));
        });
    }

    /**
     * Cherche un jeu IGDB via le nom de son exécutable Windows (alternative_names).
     */
    public List<AlternativeName> findByWindowsExecutable(String exeName, String token) {
        return apiObservations.observe("igdb", "find_by_windows_executable", () -> {
            String name = exeName.replaceAll("(?i)\\.exe$", "").replace("_", " ").replace("-", " ").trim();
            APICalypse query = new APICalypse()
                    .fields("name,game.id,game.name")
                    .where("name ~ \"" + name.replace("\"", "\\\"") + "\"")
                    .limit(1);
            return fetchOrEmpty(token, "/alternative_names", "exe=" + exeName, query,
                    q -> ProtoRequestKt.alternativeNames(IGDBWrapper.INSTANCE, q));
        });
    }

    /**
     * Recherche plein-texte d'un jeu par son nom.
     */
    public List<Game> searchByName(String name, String token) {
        return apiObservations.observe("igdb", "search_by_name", () -> {
            APICalypse query = new APICalypse().search(name).fields("id,name").limit(5);
            return fetchOrEmpty(token, "/games", "search '" + name + "'", query, IgdbClient::games);
        });
    }

    /**
     * Récupère les détails de plusieurs jeux par leurs IDs IGDB en un seul appel (batch).
     */
    public List<Game> fetchGamesByIds(List<String> igdbIds, String fields, String token) {
        if (igdbIds.isEmpty()) return List.of();
        return apiObservations.observe("igdb", "fetch_games_by_ids", () -> {
            String idList = igdbIds.stream().collect(Collectors.joining(",", "(", ")"));
            APICalypse query = new APICalypse().fields(fields).where("id=" + idList).limit(igdbIds.size());
            return fetchOrEmpty(token, "/games", "batch (" + igdbIds.size() + ")", query, IgdbClient::games);
        });
    }

    /**
     * Récupère les détails d'un jeu par son ID IGDB.
     */
    public List<Game> fetchGameById(String igdbId, String fields, String token) {
        return apiObservations.observe("igdb", "fetch_game_by_id", () -> {
            APICalypse query = new APICalypse().fields(fields).where("id=" + Long.parseLong(igdbId)).limit(1);
            return fetchOrEmpty(token, "/games", "id=" + igdbId, query, IgdbClient::games);
        });
    }

    /**
     * Récupère les détails enrichis d'un jeu (slug, summary, first_release_date,
     * websites, external_games) pour la cache stale-while-revalidate.
     */
    public Optional<Game> fetchGameDetails(String igdbId, String token) {
        return apiObservations.observe("igdb", "fetch_game_details", () -> {
            APICalypse query = new APICalypse().fields(GAME_DETAILS_FIELDS).where("id = " + Long.parseLong(igdbId)).limit(1);
            return fetchOrEmpty(token, "/games", "details id=" + igdbId, query, IgdbClient::games)
                    .stream().findFirst();
        });
    }

    private static List<Game> games(APICalypse query) throws RequestException {
        return ProtoRequestKt.games(IGDBWrapper.INSTANCE, query);
    }

    /** {@code "external_game_source=<id> & "} when filtering by source, nothing otherwise. */
    private static String sourceFilter(long sourceId) {
        return sourceId >= 0 ? "external_game_source=" + sourceId + " & " : "";
    }

    /** Runs {@code call} on the shared SDK client, logged; propagates IGDB failures. */
    private <T> List<T> fetch(String token, String endpoint, String context, APICalypse query, IgdbCall<T> call)
            throws RequestException {
        log.debug("[IGDB] {} {} — query: {}", endpoint, context, query.buildQuery());
        synchronized (IGDBWrapper.INSTANCE) {
            setCredentialsIfChanged(token);
            List<T> results = call.fetch(query);
            log.debug("[IGDB] {} {} — {} result(s)", endpoint, context, results.size());
            return results;
        }
    }

    /** {@link #fetch}, logging an IGDB failure and returning no results instead. */
    private <T> List<T> fetchOrEmpty(String token, String endpoint, String context, APICalypse query, IgdbCall<T> call) {
        try {
            return fetch(token, endpoint, context, query, call);
        } catch (RequestException e) {
            log.error("[IGDB] {} {} failed: {}", endpoint, context, e.getMessage());
            return List.of();
        }
    }

    /** Appelé à l'intérieur d'un bloc synchronized(IGDBWrapper.INSTANCE). */
    private void setCredentialsIfChanged(String token) {
        if (!token.equals(currentToken)) {
            IGDBWrapper.INSTANCE.setCredentials(clientId, token);
            currentToken = token;
        }
    }
}
