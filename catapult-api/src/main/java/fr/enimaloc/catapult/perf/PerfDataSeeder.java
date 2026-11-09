package fr.enimaloc.catapult.perf;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.igdb.IgdbGameCacheEntry;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCacheRepository;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryService;
import fr.enimaloc.catapult.service.notification.NotificationService;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Seeds the {@code perf} profile's accounts: {@code catapult.perf.accounts} streamers {@code perf-streamer-001}…
 * and {@code perf-admin}, plus a few draft experiments. Each streamer gets what the measured screens show: settings,
 * a game binding for each {@link #GAMES game}, both notification channels, {@value #NOTIFICATIONS} notifications
 * and a widget token. An account that already exists is left as it is, so a restart seeds nothing twice.
 *
 * <p>On every start, each streamer is live and playing the first game on the simulated Steam, as the presence
 * detection would see them. Every global external call is set to simulated too: the stores, which would otherwise
 * default to real, are never reached from a perf run.
 */
@Slf4j
@Component
@Profile("perf")
@DependsOn("perfGuard")
public class PerfDataSeeder implements ApplicationRunner {

    public static final String STREAMER_PREFIX = "perf-streamer-";
    public static final String ADMIN_USERNAME = "perf-admin";
    /** The admin's Twitch id: {@code app.owner-id} in the {@code perf} profile, which makes it the admin. */
    public static final String ADMIN_TWITCH_ID = "perf-admin-twitch";
    static final int NOTIFICATIONS = 5;
    private static final int EXPERIMENTS = 3;
    /** The chat command every streamer has enabled: it is what makes the channel page show its Commands tab. */
    static final String COMMAND_NAME = "!perf";

    /**
     * A game the streamers play: its Steam app, the Twitch category their binding puts the channel on, its
     * trigger warnings (set, so the startup TW backfill skips the seeded bindings instead of resolving them all)
     * and its IGDB id (in IGDB's cache: what shows the channel page's DTDD card).
     */
    public record PerfGame(String name, String steamAppId, String twitchGameId, String twitchGameName,
                           Set<String> tws, String igdbId) {}

    /**
     * The games every streamer has a binding for; the first one is the game they play at startup. The Twitch and
     * IGDB ids are stand-ins: nothing reaches Twitch nor IGDB under {@code perf}.
     */
    public static final List<PerfGame> GAMES = List.of(
            new PerfGame("Celeste", "504230", "491447", "Celeste", Set.of("suicide"), "26226"),
            new PerfGame("Hades", "1145360", "510577", "Hades", Set.of("violence_graphic"), "113112"),
            new PerfGame("Hollow Knight", "367520", "490147", "Hollow Knight", Set.of("violence_graphic"), "14593"),
            new PerfGame("Stardew Valley", "413150", "490744", "Stardew Valley", Set.of("death_of_animal"), "17000"),
            new PerfGame("Factorio", "427520", "490550", "Factorio", Set.of("violence_graphic"), "7046"));

    private final int accountCount;
    private final TransactionOperations transactions;
    private final UserAccountRepository accounts;
    private final UserSettingsRepository settings;
    private final GameBindingRepository bindings;
    private final GetterConfigRepository getters;
    private final ExperimentRepository experiments;
    private final ChatCommandDefinitionRepository commands;
    private final NotificationService notifications;
    private final NotificationDeliveryService deliveries;
    private final WidgetTokenService widgetTokens;
    private final StreamStateService streamStates;
    private final SimulationRegistry simulation;
    private final IgdbGameCacheRepository igdbCache;

    public PerfDataSeeder(
            @Value("${catapult.perf.accounts:100}") int accountCount,
            TransactionOperations transactions,
            UserAccountRepository accounts,
            UserSettingsRepository settings,
            GameBindingRepository bindings,
            GetterConfigRepository getters,
            ExperimentRepository experiments,
            ChatCommandDefinitionRepository commands,
            NotificationService notifications,
            NotificationDeliveryService deliveries,
            WidgetTokenService widgetTokens,
            StreamStateService streamStates,
            SimulationRegistry simulation,
            IgdbGameCacheRepository igdbCache) {
        this.accountCount = accountCount;
        this.transactions = transactions;
        this.accounts = accounts;
        this.settings = settings;
        this.bindings = bindings;
        this.getters = getters;
        this.experiments = experiments;
        this.commands = commands;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.widgetTokens = widgetTokens;
        this.streamStates = streamStates;
        this.simulation = simulation;
        this.igdbCache = igdbCache;
    }

    /** The username of the {@code n}th streamer (from 1). */
    public static String streamerUsername(int n) {
        return STREAMER_PREFIX + String.format(Locale.ROOT, "%03d", n);
    }

    /** The {@link #GAMES game} named {@code name} (case ignored). */
    public static Optional<PerfGame> game(String name) {
        return GAMES.stream().filter(g -> g.name().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public void run(ApplicationArguments args) {
        simulateGlobalCalls();
        transactions.executeWithoutResult(status -> igdbCache());
        UserAccount admin = transactions.execute(status -> admin());
        int created = 0;
        for (int n = 1; n <= accountCount; n++) {
            String username = streamerUsername(n);
            int number = n;
            Optional<UserAccount> existing = accounts.findByTwitchUsername(username);
            UserAccount streamer = existing.orElseGet(() -> transactions.execute(status -> streamer(number, admin)));
            if (existing.isEmpty()) {
                created++;
            }
            transactions.executeWithoutResult(status -> command(streamer));
            startPlaying(streamer);
        }
        transactions.executeWithoutResult(status -> experiments());
        log.info("[Perf] {} streamer(s) seeded, {} already there; all live on {}", created, accountCount - created,
                GAMES.getFirst().name());
    }

    private UserAccount admin() {
        return accounts.findByTwitchUsername(ADMIN_USERNAME).orElseGet(() -> {
            UserAccount admin = account(ADMIN_USERNAME, ADMIN_TWITCH_ID, null);
            settings(admin);
            widgetTokens.getOrCreate(admin);
            return admin;
        });
    }

    private UserAccount streamer(int n, UserAccount admin) {
        String username = streamerUsername(n);
        UserAccount streamer = account(username, username + "-twitch", String.format(Locale.ROOT, "765611990%08d", n));
        settings(streamer);
        for (PerfGame game : GAMES) {
            bindings.save(binding(streamer, game));
        }
        deliveries.replaceChannels(streamer, EnumSet.allOf(NotificationChannel.class));
        for (int i = 1; i <= NOTIFICATIONS; i++) {
            notifications.create(new NotificationService.CreateRequest(
                    "Perf notification " + i, "Seeded for " + username, Severity.values()[i % Severity.values().length],
                    null, null, null, streamer.getId()), admin);
        }
        widgetTokens.getOrCreate(streamer);
        return accounts.findById(streamer.getId()).orElseThrow();
    }

    /**
     * IGDB's cache entry of each game's Steam app, written again on every start (it expires): with no IGDB to ask
     * under {@code perf}, it is what tells the channel page the IGDB id of the game played (its DTDD card).
     */
    private void igdbCache() {
        for (PerfGame game : GAMES) {
            igdbCache.save(new IgdbGameCacheEntry(IgdbService.KEY_STEAM_PREFIX + game.steamAppId(), game.igdbId(),
                    game.name()));
        }
    }

    private void command(UserAccount streamer) {
        if (commands.existsByUserAndName(streamer, COMMAND_NAME)) {
            return;
        }
        ChatCommandDefinition command = new ChatCommandDefinition();
        command.setUser(streamer);
        command.setName(COMMAND_NAME);
        command.setTemplate("Perf command");
        command.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        command.setEnabled(true);
        commands.save(command);
    }

    /** Every global call simulated as succeeding; the streamers' own calls already are, by default. */
    private void simulateGlobalCalls() {
        for (CallKey key : SimulatedCalls.all()) {
            if (key.scope() == CallKey.Scope.GLOBAL) {
                simulation.set(null, key, CallSetting.ok());
            }
        }
    }

    /** Live, and playing the first game on the simulated Steam: picked up by the next detection cycle. */
    private void startPlaying(UserAccount streamer) {
        PerfGame first = GAMES.getFirst();
        simulation.update(streamer.getId(), data -> data.withLive(true).withPresence(
                Map.of(GetterConfig.Provider.STEAM, new SimulatedPresence(first.steamAppId(), first.name()))));
        streamStates.setLive(streamer, true);
    }

    private UserAccount account(String username, String twitchId, String steamId) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        account.setSteamId(steamId);
        account.setStatus(UserAccount.Status.ACTIVE);
        account.setBotEnabled(true);
        return accounts.save(account);
    }

    /** Settings and game getters as a Twitch sign-up creates them: every provider, Steam the only one enabled. */
    private void settings(UserAccount account) {
        int priority = 1;
        for (GetterConfig.Provider provider : GetterConfig.Provider.values()) {
            GetterConfig config = new GetterConfig();
            config.setUser(account);
            config.setProvider(provider);
            config.setPriority(priority++);
            config.setEnabled(provider == GetterConfig.Provider.STEAM);
            getters.save(config);
        }
        UserSettings userSettings = new UserSettings();
        userSettings.setUser(account);
        userSettings.setNoGameTwitchGameId("509658");
        userSettings.setNoGameTwitchGameName("Just Chatting");
        settings.save(userSettings);
    }

    private static GameBinding binding(UserAccount streamer, PerfGame game) {
        GameBinding binding = new GameBinding();
        binding.setUser(streamer);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId(game.steamAppId());
        binding.setSourceName(game.name());
        binding.setTwitchGameId(game.twitchGameId());
        binding.setTwitchGameName(game.twitchGameName());
        binding.setStatus(GameBinding.Status.MANUAL);
        binding.setTws(new HashSet<>(game.tws()));
        return binding;
    }

    /** A few draft experiments with a control and a treatment variant, for the admin's experiment screens. */
    private void experiments() {
        for (int i = 1; i <= EXPERIMENTS; i++) {
            String key = "perf.experiment-" + i;
            if (experiments.findByKey(key).isPresent()) {
                continue;
            }
            Experiment experiment = new Experiment();
            experiment.setKey(key);
            experiment.setName("Perf experiment " + i);
            List<ExperimentVariant> variants = new ArrayList<>();
            for (String variantKey : List.of("control", "treatment")) {
                ExperimentVariant variant = new ExperimentVariant();
                variant.setExperiment(experiment);
                variant.setKey(variantKey);
                variant.setName(variantKey);
                variant.setControl("control".equals(variantKey));
                variant.setInternalId(variants.size());
                variants.add(variant);
            }
            experiment.getVariants().addAll(variants);
            experiments.save(experiment);
        }
    }
}
