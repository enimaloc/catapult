package fr.enimaloc.catapult.simulation.external;

import com.api.igdb.exceptions.RequestException;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import proto.AlternativeName;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * IGDB, in the global {@code igdb.search} (lookups: sources, external games, executables, name search) and
 * {@code igdb.game} (game reads) modes. {@code REAL} hands the call to the real {@link IgdbClient} bean; otherwise
 * nothing is found, and a failure answers empty, as the real client does. The exception is
 * {@link #findSourcesByName}, whose real failure propagates: its simulated failure is thrown as the runner builds it
 * (an unchecked {@code RestClientException}: an IGDB {@code RequestException} wraps Fuel objects that cannot be made
 * up), which its only caller catches the same way.
 * <p>
 * A subclass rather than an extracted interface, so that none of the client's callers changes: the superclass's
 * state is never used, every public method is overridden.
 */
@Slf4j
@Primary
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedIgdbClient extends IgdbClient {

    private final ObjectProvider<IgdbClient> real;
    private final SimulatedCallRunner runner;

    public SimulatedIgdbClient(@Qualifier("igdbClient") ObjectProvider<IgdbClient> real, SimulatedCallRunner runner) {
        super(null);
        this.real = real;
        this.runner = runner;
    }

    @Override
    public List<ExternalGameSource> findSourcesByName(String name, String token) throws RequestException {
        try {
            return runner.run(null, SimulatedCalls.IGDB_SEARCH, "POST /external_game_sources " + name, () -> {
                try {
                    return real().findSourcesByName(name, token);
                } catch (RequestException e) {
                    throw new RealRequestFailure(e);
                }
            }, List::of, SimulatedCallRunner::httpError);
        } catch (RealRequestFailure e) {
            throw e.request;
        }
    }

    @Override
    public List<ExternalGame> findExternalGameByUid(String uid, long sourceId, String token) {
        return list(SimulatedCalls.IGDB_SEARCH, "POST /external_games uid=" + uid,
                () -> real().findExternalGameByUid(uid, sourceId, token));
    }

    @Override
    public List<ExternalGame> findExternalGamesByUids(List<String> uids, long sourceId, String token) {
        if (uids.isEmpty()) return List.of();
        return list(SimulatedCalls.IGDB_SEARCH, "POST /external_games batch (" + uids.size() + ")",
                () -> real().findExternalGamesByUids(uids, sourceId, token));
    }

    @Override
    public List<AlternativeName> findByWindowsExecutable(String exeName, String token) {
        return list(SimulatedCalls.IGDB_SEARCH, "POST /alternative_names exe=" + exeName,
                () -> real().findByWindowsExecutable(exeName, token));
    }

    @Override
    public List<Game> searchByName(String name, String token) {
        return list(SimulatedCalls.IGDB_SEARCH, "POST /games search '" + name + "'",
                () -> real().searchByName(name, token));
    }

    @Override
    public List<Game> fetchGamesByIds(List<String> igdbIds, String fields, String token) {
        if (igdbIds.isEmpty()) return List.of();
        return list(SimulatedCalls.IGDB_GAME, "POST /games batch (" + igdbIds.size() + ")",
                () -> real().fetchGamesByIds(igdbIds, fields, token));
    }

    @Override
    public List<Game> fetchGameById(String igdbId, String fields, String token) {
        return list(SimulatedCalls.IGDB_GAME, "POST /games id=" + igdbId,
                () -> real().fetchGameById(igdbId, fields, token));
    }

    @Override
    public Optional<Game> fetchGameDetails(String igdbId, String token) {
        try {
            return runner.run(null, SimulatedCalls.IGDB_GAME, "POST /games details id=" + igdbId,
                    () -> real().fetchGameDetails(igdbId, token), Optional::empty, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated IGDB {} failed: {}", SimulatedCalls.IGDB_GAME.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private <T> List<T> list(CallKey key, String request, Supplier<List<T>> realCall) {
        try {
            return runner.run(null, key, request, realCall, List::of, SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("Simulated IGDB {} failed: {}", key.id(), e.getMessage());
            return List.of();
        }
    }

    private IgdbClient real() {
        IgdbClient client = real.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("The real IGDB client is not available");
        }
        return client;
    }

    /** Carries a real {@link RequestException} through the runner's supplier, unwrapped by the caller. */
    private static final class RealRequestFailure extends RuntimeException {
        private final transient RequestException request;

        RealRequestFailure(RequestException request) {
            super(request);
            this.request = request;
        }
    }
}
