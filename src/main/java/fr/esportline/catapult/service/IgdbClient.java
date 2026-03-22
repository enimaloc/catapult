package fr.esportline.catapult.service;

import com.api.igdb.apicalypse.APICalypse;
import com.api.igdb.apicalypse.Sort;
import com.api.igdb.exceptions.RequestException;
import com.api.igdb.request.IGDBWrapper;
import com.api.igdb.request.ProtoRequestKt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class IgdbClient {

    @Value("${app.igdb.client-id:}")
    private String clientId;

    private String currentToken = "";

    /**
     * Résout les sources externes par nom (ex: "Steam").
     * Propage RequestException — l'appelant gère l'erreur.
     */
    public List<ExternalGameSource> findSourcesByName(String name, String token) throws RequestException {
        APICalypse query = new APICalypse().fields("id,name").where("name = \"" + name + "\"").limit(1);
        log.debug("[IGDB] /external_game_sources — query: {}", query.buildQuery());
        synchronized (IGDBWrapper.INSTANCE) {
            setCredentialsIfChanged(token);
            List<ExternalGameSource> results = ProtoRequestKt.externalGameSources(IGDBWrapper.INSTANCE, query);
            log.debug("[IGDB] /external_game_sources — {} result(s)", results.size());
            return results;
        }
    }

    /**
     * Cherche un jeu externe par son uid (Steam appId) — lookup unitaire.
     * sourceId >= 0 → filtre par external_game_source ; sinon pas de filtre source.
     */
    public List<ExternalGame> findExternalGameByUid(String uid, long sourceId, String token) {
        String where = sourceId >= 0
            ? "external_game_source=" + sourceId + " & uid=\"" + uid + "\""
            : "uid=\"" + uid + "\"";
        APICalypse query = new APICalypse().fields("uid,game.id,game.name").where(where).limit(1);
        log.debug("[IGDB] /external_games uid={} — query: {}", uid, query.buildQuery());
        try {
            synchronized (IGDBWrapper.INSTANCE) {
                setCredentialsIfChanged(token);
                List<ExternalGame> results = ProtoRequestKt.externalGames(IGDBWrapper.INSTANCE, query);
                log.debug("[IGDB] /external_games uid={} — {} result(s)", uid, results.size());
                return results;
            }
        } catch (RequestException e) {
            log.error("[IGDB] /external_games uid={} failed: {}", uid, e.getMessage());
            return List.of();
        }
    }

    /**
     * Cherche plusieurs jeux externes par leurs uids — batch (max 500 par appel).
     * sourceId >= 0 → filtre par external_game_source ; sinon pas de filtre source.
     */
    public List<ExternalGame> findExternalGamesByUids(List<String> uids, long sourceId, String token) {
        if (uids.isEmpty()) return List.of();
        String uidList = uids.stream()
            .map(u -> "\"" + u + "\"")
            .collect(Collectors.joining(",", "(", ")"));
        String where = sourceId >= 0
            ? "external_game_source=" + sourceId + " & uid=" + uidList
            : "uid=" + uidList;
        APICalypse query = new APICalypse().fields("uid,game.id,game.name").where(where).limit(uids.size());
        log.debug("[IGDB] /external_games batch ({}) — query: {}", uids.size(), query.buildQuery());
        try {
            synchronized (IGDBWrapper.INSTANCE) {
                setCredentialsIfChanged(token);
                List<ExternalGame> results = ProtoRequestKt.externalGames(IGDBWrapper.INSTANCE, query);
                log.debug("[IGDB] /external_games batch — {} result(s)", results.size());
                return results;
            }
        } catch (RequestException e) {
            log.error("[IGDB] /external_games batch failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Recherche plein-texte d'un jeu par son nom.
     */
    public List<Game> searchByName(String name, String token) {
        APICalypse query = new APICalypse().search(name).fields("id,name").limit(5);
        log.debug("[IGDB] /games search — query: {}", query.buildQuery());
        try {
            synchronized (IGDBWrapper.INSTANCE) {
                setCredentialsIfChanged(token);
                List<Game> results = ProtoRequestKt.games(IGDBWrapper.INSTANCE, query);
                log.debug("[IGDB] /games search — {} result(s)", results.size());
                return results;
            }
        } catch (RequestException e) {
            log.error("[IGDB] /games search failed for '{}': {}", name, e.getMessage());
            return List.of();
        }
    }

    /**
     * Récupère les détails d'un jeu par son ID IGDB.
     */
    public List<Game> fetchGameById(String igdbId, String fields, String token) {
        APICalypse query = new APICalypse().fields(fields).where("id=" + Long.parseLong(igdbId)).limit(1);
        log.debug("[IGDB] /games by id — query: {}", query.buildQuery());
        try {
            synchronized (IGDBWrapper.INSTANCE) {
                setCredentialsIfChanged(token);
                List<Game> results = ProtoRequestKt.games(IGDBWrapper.INSTANCE, query);
                log.debug("[IGDB] /games by id — {} result(s)", results.size());
                return results;
            }
        } catch (RequestException e) {
            log.error("[IGDB] /games by id failed for {}: {}", igdbId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Récupère une page de jeux triés par popularité (preload).
     */
    public List<Game> fetchGamePage(int limit, int offset, String token) {
        APICalypse query = new APICalypse()
            .fields("id,name,external_games.uid,external_games.external_game_source,age_ratings.id,age_ratings.category,age_ratings.rating")
            .sort("aggregated_rating", Sort.DESCENDING)
            .limit(limit)
            .offset(offset);
        log.debug("[IGDB] /games page — query: {}", query.buildQuery());
        try {
            synchronized (IGDBWrapper.INSTANCE) {
                setCredentialsIfChanged(token);
                List<Game> results = ProtoRequestKt.games(IGDBWrapper.INSTANCE, query);
                log.debug("[IGDB] /games page — {} result(s)", results.size());
                return results;
            }
        } catch (RequestException e) {
            log.error("[IGDB] /games page failed (limit={}, offset={}): {}", limit, offset, e.getMessage());
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
