package fr.enimaloc.catapult.service.enrichment.chess;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chess.ChessAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.chess.ChessAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's chess stats on the accounts they declared (Chess.com, Lichess), through the shared cache under
 * {@code u:<uuid>:...} keys. An absence the site confirmed (renamed or closed account) is cached
 * ({@code not_found}); an outage (any failure of the fetch) is never cached and says {@code unavailable}. A site
 * not declared, or whose source is off, gives no object; neither gives no stats at all.
 */
@Service
public class ChessStatsService {

    /**
     * The cached shapes. A value that is null is a recognised "not found", cached as a value (so under the source's
     * own TTL, not as a known absence).
     */
    public record ChesscomStatsCell(ChesscomStats value) {
    }

    public record ChesscomGamesCell(List<ChesscomDailyGame> value) {
    }

    public record LichessUserCell(LichessUser value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final ChessClient client;
    private final EnrichmentProperties properties;
    private final ChessAccountRepository accounts;

    public ChessStatsService(EnrichmentCache cache, ChessClient client, EnrichmentProperties properties,
                             ChessAccountRepository accounts) {
        this.cache = cache;
        this.client = client;
        this.properties = properties;
        this.accounts = accounts;
    }

    /** The member's chess stats when the game played is chess (by IGDB id, else by the detected name). */
    public Optional<ChessStats> forDetected(UserAccount user, DetectedGame detected, String igdbId,
                                           FetchBudget budget) {
        return PublisherGames.chess(detected, igdbId) ? forMember(user, budget, false) : Optional.empty();
    }

    /**
     * The member's chess stats; empty when they declared no account (or only on sites whose source is off).
     * {@code detail} also lists the Chess.com daily games in progress.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<ChessStats> forMember(UserAccount user, FetchBudget budget, boolean detail) {
        Optional<ChessAccount> row = accounts.findById(user.getId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String member = "u:" + user.getId();
        ChessAccount account = row.get();
        ChessStats.Chesscom chesscom = account.getChesscomUsername() != null
                && properties.source(EnrichmentSource.CHESS_CHESSCOM_STATS).enabled()
                ? chesscom(account.getChesscomUsername(), member, budget, detail) : null;
        ChessStats.Lichess lichess = account.getLichessId() != null
                && properties.source(EnrichmentSource.CHESS_LICHESS_USER).enabled()
                ? lichess(account.getLichessId(), account.getLichessUsername(), member, budget) : null;
        if (chesscom == null && lichess == null) {
            return Optional.empty();
        }
        Status status = chesscom != null && chesscom.status() == Status.OK
                || lichess != null && lichess.status() == Status.OK ? Status.OK
                : chesscom != null ? chesscom.status() : lichess.status();
        String shown = chesscom != null ? chesscom.username() : lichess.username();
        return Optional.of(new ChessStats(status, ChessStats.GAME, shown, chesscom, lichess, null));
    }

    private ChessStats.Chesscom chesscom(String username, String member, FetchBudget budget, boolean detail) {
        String url = ChessProfiles.chesscom(username);
        String key = member + ":chesscom:" + username;
        Looked<ChesscomStats> stats = look(EnrichmentSource.CHESS_CHESSCOM_STATS, key, ChesscomStatsCell.class,
                ChesscomStatsCell::new, ChesscomStatsCell::value, budget, () -> {
                    // the profile first: a closed account is not found, its frozen ratings are not served
                    client.chesscomPlayer(username);
                    return client.chesscomStats(username);
                });
        if (stats.value().isEmpty()) {
            return new ChessStats.Chesscom(stats.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND, username, url,
                    null, null, null, null, null);
        }
        List<ChesscomDailyGame> games = null;
        if (properties.source(EnrichmentSource.CHESS_CHESSCOM_GAMES).enabled()) {
            // the games are a part: without them the ratings are still served
            games = look(EnrichmentSource.CHESS_CHESSCOM_GAMES, key + ":games", ChesscomGamesCell.class,
                    ChesscomGamesCell::new, ChesscomGamesCell::value, budget, () -> client.chesscomGames(username))
                    .value().orElse(null);
        }
        ChesscomStats s = stats.value().get();
        return new ChessStats.Chesscom(Status.OK, username, url, s.modes(), s.fide(),
                games == null ? null : games.size(),
                games == null ? null : (int) games.stream().filter(ChesscomDailyGame::yourTurn).count(),
                detail ? games : null);
    }

    private ChessStats.Lichess lichess(String id, String username, String member, FetchBudget budget) {
        Looked<LichessUser> user = look(EnrichmentSource.CHESS_LICHESS_USER, member + ":lichess:" + id,
                LichessUserCell.class, LichessUserCell::new, LichessUserCell::value, budget,
                () -> client.lichessUser(id));
        if (user.value().isEmpty()) {
            return new ChessStats.Lichess(user.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND, username,
                    ChessProfiles.lichess(username), null, null, null, null, null, null, null, null, null);
        }
        LichessUser u = user.value().get();
        return new ChessStats.Lichess(Status.OK, u.username(),
                u.url() != null ? u.url() : ChessProfiles.lichess(u.username()), u.title(), u.perfs(), u.games(),
                u.win(), u.loss(), u.draw(), u.playingUrl() != null, u.playingUrl(), u.seenAt());
    }

    /**
     * The cached or fetched value. A recognised "not found" is cached as a value (a cell with no content, under the
     * source's TTL); any other failure of the fetch is an outage: {@code failed} is set and nothing is cached.
     */
    private <T, W> Looked<T> look(EnrichmentSource source, String key, Class<W> cellType, Function<T, W> wrap,
                                  Function<W, T> unwrap, FetchBudget budget, Supplier<T> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        Optional<W> cell = cache.get(source, key, cellType, budget, () -> {
            try {
                return Optional.of(wrap.apply(fetch.get()));
            } catch (ChessNotFoundException e) {
                return Optional.of(wrap.apply(null));
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(cell.map(unwrap), failed.get());
    }
}
