package fr.enimaloc.catapult.service.chess;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.ChessData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chess.ChessAccount;
import fr.enimaloc.catapult.repository.chess.ChessAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.chess.ChessClient;
import fr.enimaloc.catapult.service.enrichment.chess.ChessNotFoundException;
import fr.enimaloc.catapult.service.enrichment.chess.ChessProfiles;
import fr.enimaloc.catapult.service.enrichment.chess.ChessSite;
import fr.enimaloc.catapult.service.enrichment.chess.ChesscomPlayer;
import fr.enimaloc.catapult.service.enrichment.chess.LichessUser;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A member's chess accounts: the Chess.com and the Lichess names they declare. Each name is checked with its site
 * before anything is written, which also gives the stable id kept next to it (an unknown or closed account is a
 * 400, the site out of reach or limited a 503). As with any declared name, nothing stops a member from declaring
 * someone else's account: what is read with it is only ever served to the member. The row exists only while it
 * holds one account. Each write re-reads the row and is optimistically locked: one that crosses another is redone
 * once on the fresh row, then answers 409. A change forgets the member's cached stats; telling the owner's pages
 * comes after the write and never fails it.
 */
@Slf4j
@Service
public class ChessAccountService {

    /** Chess.com names: 3 to 25 letters, digits, {@code _} or {@code -}. */
    private static final Pattern CHESSCOM_USERNAME = Pattern.compile("[A-Za-z0-9_-]{3,25}");
    /** Lichess names: 2 to 30 letters, digits, {@code _} or {@code -}, starting with a letter or a digit. */
    private static final Pattern LICHESS_USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,29}");

    /** What a change of the accounts makes stale; the member's other stats stay cached. */
    static final List<EnrichmentSource> CHESS_SOURCES = List.of(EnrichmentSource.CHESS_CHESSCOM_STATS,
            EnrichmentSource.CHESS_CHESSCOM_GAMES, EnrichmentSource.CHESS_LICHESS_USER);

    private final ChessAccountRepository accounts;
    private final ChessClient client;
    private final EnrichmentCache cache;
    private final ChannelEventPublisher events;
    private final Clock clock;

    @Autowired
    public ChessAccountService(ChessAccountRepository accounts, ChessClient client, EnrichmentCache cache,
                               ChannelEventPublisher events) {
        this(accounts, client, cache, events, Clock.systemUTC());
    }

    ChessAccountService(ChessAccountRepository accounts, ChessClient client, EnrichmentCache cache,
                        ChannelEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.clock = clock;
    }

    /** Declares {@code username} (trimmed) as the member's Chess.com account, once Chess.com knows it. */
    public void saveChesscom(UserAccount user, String username) {
        String name = trimmed(username);
        if (!CHESSCOM_USERNAME.matcher(name).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Chess.com username");
        }
        ChesscomPlayer found = lookUp(ChessSite.CHESSCOM, () -> client.chesscomPlayer(name));
        write(user.getId(), true, row -> {
            row.setChesscomUsername(found.username());
            row.setChesscomPlayerId(found.playerId());
            return true;
        });
        changed(user.getId());
    }

    public void deleteChesscom(UserAccount user) {
        if (write(user.getId(), false, row -> {
            if (row.getChesscomUsername() == null) {
                return false;
            }
            row.setChesscomUsername(null);
            row.setChesscomPlayerId(null);
            return true;
        })) {
            changed(user.getId());
        }
    }

    /** Declares {@code username} (trimmed) as the member's Lichess account, once Lichess knows it (and it is open). */
    public void saveLichess(UserAccount user, String username) {
        String name = trimmed(username);
        if (!LICHESS_USERNAME.matcher(name).matches()) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Invalid Lichess username");
        }
        LichessUser found = lookUp(ChessSite.LICHESS, () -> client.lichessUser(name));
        write(user.getId(), true, row -> {
            row.setLichessId(found.id());
            row.setLichessUsername(found.username());
            return true;
        });
        changed(user.getId());
    }

    public void deleteLichess(UserAccount user) {
        if (write(user.getId(), false, row -> {
            if (row.getLichessId() == null) {
                return false;
            }
            row.setLichessId(null);
            row.setLichessUsername(null);
            return true;
        })) {
            changed(user.getId());
        }
    }

    /** The settings card's data; built from the row (read once) only, never calling a chess site. */
    public ChessData data(UserAccount user) {
        return data(user.getId());
    }

    private ChessData data(UUID userId) {
        Optional<ChessAccount> row = accounts.findById(userId);
        String chesscom = row.map(ChessAccount::getChesscomUsername).orElse(null);
        String lichess = row.map(ChessAccount::getLichessUsername).orElse(null);
        return new ChessData(chesscom, chesscom == null ? null : ChessProfiles.chesscom(chesscom),
                lichess, lichess == null ? null : ChessProfiles.lichess(lichess));
    }

    /** The member's cached chess stats were read with the old accounts: forgotten; then the owner's pages are told. */
    private void changed(UUID userId) {
        cache.evictUser(userId, CHESS_SOURCES);
        try {
            events.chessAccountChanged(userId, data(userId));
        } catch (RuntimeException e) {
            // The settings are saved: a page that is not told refreshes on its next load.
            log.warn("Chess settings of {} saved but not pushed: {}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Applies {@code change} to the member's row as it is now ({@code create}: a missing row is started), then saves
     * it, or deletes it once it holds no account. A write that crosses another one is redone once on the fresh row,
     * then is a 409.
     *
     * @return false when nothing was written: no row (and {@code create} false) or {@code change} changed nothing
     */
    private boolean write(UUID userId, boolean create, Predicate<ChessAccount> change) {
        try {
            return writeOnce(userId, create, change);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException first) {
            try {
                return writeOnce(userId, create, change);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException again) {
                log.warn("Chess settings of {} crossed another write twice: {}", userId,
                        again.getClass().getSimpleName());
                throw new ApiProblem(ErrorCode.CONFLICT, "The chess settings changed meanwhile, try again");
            }
        }
    }

    private boolean writeOnce(UUID userId, boolean create, Predicate<ChessAccount> change) {
        Optional<ChessAccount> found = accounts.findById(userId);
        if (found.isEmpty() && !create) {
            return false;
        }
        ChessAccount row = found.orElseGet(() -> new ChessAccount(userId));
        if (!change.test(row)) {
            return false;
        }
        if (!row.holdsAny()) {
            if (found.isPresent()) {
                accounts.delete(row);
            }
        } else {
            row.setUpdatedAt(clock.instant());
            accounts.save(row);
        }
        return true;
    }

    /** One check with the site: its recognised "not found" is a 400, anything else that fails a 503. */
    private static <T> T lookUp(ChessSite site, Supplier<T> call) {
        String name = site == ChessSite.CHESSCOM ? "Chess.com" : "Lichess";
        try {
            return call.get();
        } catch (ChessNotFoundException e) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown " + name + " username");
        } catch (RuntimeException e) {
            log.warn("{} settings check failed: {}", name, e.getClass().getSimpleName());
            throw new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, name + " cannot be reached right now");
        }
    }

    private static String trimmed(String username) {
        return username == null ? "" : username.strip();
    }
}
