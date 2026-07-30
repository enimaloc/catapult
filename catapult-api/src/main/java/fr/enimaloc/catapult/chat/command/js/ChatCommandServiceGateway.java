package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.domain.UserAccount;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Curated access to external services from sandboxed command JS.
 * Phase 1 scope: read-only lookups, no writes, no arbitrary network access.
 */
public interface ChatCommandServiceGateway {
    Optional<IgdbGame> igdbGame(String query);
    Optional<String> twitchOwnDisplayName(UserAccount user);
    Optional<String> steamPrice(String appId);

    Optional<Object> steamGame(String appId, @Nullable String locale);

    /**
     * IGDB sub-data not exposed on {@link IgdbGame} itself — kept behind their own {@code
     * igdb#get*} functions (each taking either an {@code IgdbGame} result or a bare id, see
     * {@code fr.enimaloc.catapult.chat.command.registry.igdb.IgdbIdArg}) instead of being crammed
     * into one ever-growing object, so a streamer only pays for (and the Blocks editor only
     * needs to render sockets for) the sub-data a given command actually uses.
     *
     * <p>Lists/maps, not pre-joined strings — the sandbox's {@code HostAccess} allows both
     * {@code allowMapAccess} and {@code allowListAccess}, so JS can index/iterate/read fields
     * directly ({@code igdb.getInvolvedCompanies(g)[0].developer}) instead of a streamer having
     * to split a formatted string back apart.
     */
    Optional<Map<String, String>> igdbExternalPlatforms(String igdbId);
    Optional<List<String>> igdbGenres(String igdbId);
    Optional<Map<String, Object>> igdbCover(String igdbId);
    Optional<List<String>> igdbScreenshots(String igdbId);
    Optional<List<Map<String, Object>>> igdbVideos(String igdbId);
    Optional<List<String>> igdbGameModes(String igdbId);
    Optional<List<String>> igdbThemes(String igdbId);
    Optional<List<String>> igdbPlayerPerspectives(String igdbId);
    Optional<List<Map<String, Object>>> igdbInvolvedCompanies(String igdbId);
    Optional<List<Map<String, Object>>> igdbAgeRatings(String igdbId);
    Optional<List<String>> igdbFranchises(String igdbId);
    Optional<List<String>> igdbKeywords(String igdbId);
    Optional<List<String>> igdbSimilarGames(String igdbId);
    Optional<List<String>> igdbDlcs(String igdbId);

    /** The fields IGDB actually returns for a game search — {@code igdb#getGame}'s return shape. */
    record IgdbGame(String id, String name, String summary, String releaseDate, String rating,
                     String criticRating, String platforms, String igdbUrl) {}
}
