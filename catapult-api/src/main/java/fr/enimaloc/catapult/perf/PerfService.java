package fr.enimaloc.catapult.perf;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.simulation.SimulationActions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What the {@code perf} routes do, on the {@code perf-*} accounts {@link PerfDataSeeder} seeded only (any other
 * account is unknown here): sign one in without Twitch, list them, and make one of them play a game.
 */
@Service
@Profile("perf")
@DependsOn("perfGuard")
public class PerfService {

    private static final String ACCOUNT_PREFIX = "perf-";
    /** A perf token never outlives a run, whatever {@code app.jwt.expiry-hours} says. */
    static final Duration TOKEN_VALIDITY = Duration.ofHours(12);

    private final UserAccountRepository accounts;
    private final JwtService jwtService;
    private final SimulationActions simulation;
    private final String ownerId;

    public PerfService(
            UserAccountRepository accounts,
            JwtService jwtService,
            SimulationActions simulation,
            @Value("${app.owner-id:}") String ownerId) {
        this.accounts = accounts;
        this.jwtService = jwtService;
        this.simulation = simulation;
        this.ownerId = ownerId;
    }

    public record PerfToken(String token) {}

    public record PerfAccount(String username, UUID userId, UUID widgetToken) {}

    /**
     * The JWT the Twitch login would issue to {@code username}: {@code ROLE_USER}, and {@code ROLE_ADMIN} when its
     * Twitch id is {@code app.owner-id}, the rule of {@code CatapultOAuth2UserService}; it expires after
     * {@link #TOKEN_VALIDITY}. 404 for an unknown account.
     */
    public PerfToken login(String username) {
        UserAccount account = account(username);
        List<String> roles = new ArrayList<>(List.of("ROLE_USER"));
        if (!ownerId.isBlank() && ownerId.equals(account.getTwitchId())) {
            roles.add("ROLE_ADMIN");
        }
        return new PerfToken(jwtService.generateForUser(account, roles, TOKEN_VALIDITY));
    }

    /** Every seeded account, by username. */
    public List<PerfAccount> accounts() {
        return accounts.findAll().stream()
                .filter(PerfService::isPerfAccount)
                .sorted(Comparator.comparing(UserAccount::getTwitchUsername))
                .map(a -> new PerfAccount(a.getTwitchUsername(), a.getId(), a.getWidgetToken()))
                .toList();
    }

    /**
     * {@code username} starts playing {@code name}: the simulated Steam reports it, then a detection cycle runs for
     * them, the one the scheduled poll runs (getters, game state, {@code GameDetectedEvent}, category chain). A game of
     * {@link PerfDataSeeder#GAMES} lands on its binding; any other name on a new binding, as an unknown game would.
     * 404 for an unknown account or one without Steam.
     */
    public void playGame(String username, String name) {
        UserAccount account = account(username);
        if (account.getSteamId() == null) {
            throw unknown();
        }
        String appId = PerfDataSeeder.game(name)
                .map(PerfDataSeeder.PerfGame::steamAppId)
                .orElseGet(() -> "perf-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        simulation.play(account, GetterConfig.Provider.STEAM, appId, name);
    }

    private UserAccount account(String username) {
        return accounts.findByTwitchUsername(username)
                .filter(PerfService::isPerfAccount)
                .orElseThrow(PerfService::unknown);
    }

    private static boolean isPerfAccount(UserAccount account) {
        return account.getTwitchUsername() != null && account.getTwitchUsername().startsWith(ACCOUNT_PREFIX);
    }

    private static ResponseStatusException unknown() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown perf account");
    }
}
