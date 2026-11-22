package fr.enimaloc.catapult.service.enrichment.steamstats;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.AchievementSummary;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.steam.SteamLanguage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A member's Steam play time and achievements on one game, through the shared cache: the library per member
 * ({@code steam-library} / {@code u:<uuid>}), the member's achievements per game
 * ({@code steam-achievements} / {@code u:<uuid>:app:<appId>}), and the two game-wide, member-independent sources,
 * the schema ({@code steam-schema} / {@code app:<appId>:<lang>}) and the rarity ({@code steam-rarity} /
 * {@code app:<appId>}). An outage is never cached: the stats say {@code UNAVAILABLE}, and the next call asks again.
 */
@Slf4j
@Service
public class SteamStatsService {

    /** The cached shape of the rarity of an app (the cache stores one object per key). */
    public record Rarity(Map<String, BigDecimal> percentages) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final SteamStatsClient client;
    private final TokenEncryptionService encryption;
    private final EnrichmentProperties properties;
    private final ExecutorService executor;

    @Autowired
    public SteamStatsService(EnrichmentCache cache, SteamStatsClient client, TokenEncryptionService encryption,
                             EnrichmentProperties properties) {
        this(cache, client, encryption, properties, Executors.newVirtualThreadPerTaskExecutor());
    }

    SteamStatsService(EnrichmentCache cache, SteamStatsClient client, TokenEncryptionService encryption,
                      EnrichmentProperties properties, ExecutorService executor) {
        this.cache = cache;
        this.client = client;
        this.encryption = encryption;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * The member's stats on Steam app {@code appId}; empty when the member has no linked Steam account or the
     * source is off. {@code detail} also lists every achievement.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<PlatformStats> forGame(UserAccount user, String appId, Locale locale, FetchBudget budget,
                                           boolean detail) {
        if (user.getSteamId() == null || user.getSteamId().isBlank()
                || !properties.source(EnrichmentSource.STEAM_LIBRARY).enabled()) {
            return Optional.empty();
        }
        String steamId = user.getSteamId();
        String personalKey = personalKey(user);
        String lang = SteamLanguage.fromLocale(locale).toString();
        String member = "u:" + user.getId();

        Looked<SteamLibrary> library = look(EnrichmentSource.STEAM_LIBRARY, member, SteamLibrary.class, budget,
                () -> client.library(steamId, personalKey));
        if (library.failed() && library.value().isEmpty()) {
            return unavailable(appId);
        }
        if (library.value().isEmpty()) {
            return Optional.of(PlatformStats.of(Status.PRIVATE, appId));
        }
        SteamLibrary.Entry owned = library.value().get().games().get(appId);
        if (owned == null) {
            return Optional.of(PlatformStats.of(Status.NOT_OWNED, appId));
        }

        if (!properties.source(EnrichmentSource.STEAM_ACHIEVEMENTS).enabled()
                || !properties.source(EnrichmentSource.STEAM_SCHEMA).enabled()) {
            // Not knowing the achievements is not "the game has none": the play time is still served.
            return Optional.of(stats(Status.UNAVAILABLE, appId, owned, null, null));
        }
        CompletableFuture<Looked<SteamPlayerAchievements>> unlocked = CompletableFuture.supplyAsync(() ->
                look(EnrichmentSource.STEAM_ACHIEVEMENTS, member + ":app:" + appId, SteamPlayerAchievements.class,
                        budget, () -> Optional.of(client.playerAchievements(steamId, appId, lang, personalKey))),
                executor);
        CompletableFuture<Looked<SteamAchievementSchema>> schema = CompletableFuture.supplyAsync(() ->
                look(EnrichmentSource.STEAM_SCHEMA, "app:" + appId + ":" + lang, SteamAchievementSchema.class,
                        budget, () -> client.schema(appId, lang)), executor);
        CompletableFuture<Looked<Rarity>> rarity = CompletableFuture.supplyAsync(() ->
                look(EnrichmentSource.STEAM_RARITY, "app:" + appId, Rarity.class, budget,
                        () -> Optional.of(new Rarity(client.globalPercentages(appId)))), executor);

        Looked<SteamPlayerAchievements> mine = join(unlocked);
        Looked<SteamAchievementSchema> definitions = join(schema);
        Looked<Rarity> percentages = join(rarity);

        if (mine.value().isEmpty() || mine.value().get().status() == Status.UNAVAILABLE) {
            return unavailable(appId);
        }
        Status status = mine.value().get().status();
        if (status == Status.PRIVATE) {
            return Optional.of(PlatformStats.of(Status.PRIVATE, appId));
        }
        if (status == Status.NO_ACHIEVEMENTS || definitions.value().isPresent()
                && definitions.value().get().achievements().isEmpty()) {
            return Optional.of(stats(Status.NO_ACHIEVEMENTS, appId, owned, null, null));
        }
        if (definitions.value().isEmpty()) {
            // An outage of the schema is an outage; a known absence of it means the app defines no achievement.
            return definitions.failed() ? unavailable(appId)
                    : Optional.of(stats(Status.NO_ACHIEVEMENTS, appId, owned, null, null));
        }
        List<Achievement> all = achievements(definitions.value().get(), mine.value().get(),
                percentages.value().map(Rarity::percentages).orElse(Map.of()));
        return Optional.of(stats(Status.OK, appId, owned, AchievementSummary.of(all), detail ? all : null));
    }

    /** Stops the workers of the parallel lookups. */
    @PreDestroy
    void close() {
        executor.shutdown();
    }

    private static PlatformStats stats(Status status, String appId, SteamLibrary.Entry owned,
                                       AchievementSummary summary, List<Achievement> all) {
        return new PlatformStats(status, appId, owned.playtimeMinutes(), owned.playtimeTwoWeeksMinutes(),
                owned.lastPlayed(), summary, all, Map.of());
    }

    private static List<Achievement> achievements(SteamAchievementSchema schema, SteamPlayerAchievements mine,
                                                  Map<String, BigDecimal> percentages) {
        List<Achievement> all = new ArrayList<>();
        for (SteamAchievementSchema.Definition definition : schema.achievements()) {
            boolean unlocked = mine.unlocked().containsKey(definition.apiName());
            boolean hidden = definition.hidden() && !unlocked;
            all.add(new Achievement(definition.apiName(), hidden ? null : definition.displayName(),
                    hidden ? null : definition.description(), unlocked ? definition.icon() : definition.iconGray(),
                    unlocked, unlocked ? mine.unlocked().get(definition.apiName()) : null,
                    percentages.get(definition.apiName()), null, definition.hidden()));
        }
        return List.copyOf(all);
    }

    private static Optional<PlatformStats> unavailable(String appId) {
        return Optional.of(PlatformStats.of(Status.UNAVAILABLE, appId));
    }

    /**
     * The cached or fetched value; when the fetch fails and nothing stale can be served, {@code failed} says it
     * was an outage (the cache itself answers an empty value both for "absent" and for a failed fetch).
     */
    private <T> Looked<T> look(EnrichmentSource source, String key, Class<T> type, FetchBudget budget,
                               Supplier<Optional<T>> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        Optional<T> value = cache.get(source, key, type, budget, () -> {
            try {
                return fetch.get();
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(value, failed.get());
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** The member's own Web API key, decrypted; null (so the pool's) when there is none or it cannot be read. */
    private String personalKey(UserAccount user) {
        if (user.getSteamPersonalToken() == null || user.getSteamPersonalToken().isBlank()) {
            return null;
        }
        try {
            return encryption.decrypt(user.getSteamPersonalToken());
        } catch (RuntimeException e) {
            log.warn("Steam personal key of {} not decrypted: {}", user.getId(), e.getClass().getSimpleName());
            return null;
        }
    }
}
