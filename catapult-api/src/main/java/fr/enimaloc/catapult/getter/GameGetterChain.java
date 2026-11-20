package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.steam.SteamGameGetter;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.service.emergency.EmergencyStopService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the chain of game getters in the user's priority order.
 * Stops at the first getter that returns a result.
 * When a getter fails, moves on to the next without interrupting the cycle.
 * When a getter times out, throws {@link DetectionTimeoutException}: its silence is not a "no game".
 * A provider under an emergency stop is not asked at all and freezes the detection the same way
 * ({@link ProviderStoppedException}), whatever the lower-priority providers would answer.
 * For a member in debug mode, each provider's answer goes to the {@link DetectionTrace} of the cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameGetterChain {

    private final GetterConfigRepository getterConfigRepository;
    private final Optional<SteamGameGetter> steamGameGetter;
    private final Optional<XboxGameGetter> xboxGameGetter;
    private final Optional<BattleNetGameGetter> battleNetGameGetter;
    private final Optional<MinecraftPresenceGetter> minecraftPresenceGetter;
    private final Optional<AnkamaGameGetter> ankamaGameGetter;
    private final Optional<EpicGameGetter> epicGameGetter;
    private final MeterRegistry meterRegistry;
    private final EmergencyStopService emergencyStops;

    public Optional<DetectedGame> resolve(UserAccount user) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Map<GetterConfig.Provider, GameGetter> getterByProvider = buildGetterMap();

        List<GetterConfig> configs = getterConfigRepository.findByUserOrderByPriorityAsc(user);

        for (int i = 0; i < configs.size(); i++) {
            GetterConfig config = configs.get(i);
            String provider = config.getProvider().name();
            if (!config.isEnabled()) {
                DetectionTrace.provider(provider, "disabled", null, null);
                continue;
            }
            GameGetter getter = getterByProvider.get(config.getProvider());
            if (getter == null) {
                DetectionTrace.provider(provider, "unavailable", "no getter: the provider is turned off", null);
                continue;
            }

            if (emergencyStops.isStopped(user, config.getProvider())) {
                stopSample(sample, "stopped");
                ProviderStoppedException stopped = new ProviderStoppedException(config.getProvider());
                DetectionTrace.provider(provider, "stopped", stopped.getMessage(), null);
                traceNotAsked(configs, i + 1);
                throw stopped;
            }

            try {
                Optional<DetectedGame> result = getter.getCurrentGame(user);
                if (result.isPresent()) {
                    log.debug("Game detected for user {} via {}: {}", user.getId(), config.getProvider(), result.get().getSourceName());
                    if (DetectionTrace.active()) {
                        DetectedGame game = result.get();
                        DetectionTrace.provider(provider, "found", null, DetectionTrace.details(
                            "sourceId", game.getSourceId(), "sourceType", game.getSourceType(),
                            "sourceName", game.getSourceName()));
                        traceNotAsked(configs, i + 1);
                    }
                    stopSample(sample, config.getProvider().name().toLowerCase());
                    return result;
                }
                DetectionTrace.provider(provider, "none", null, null);
            } catch (DetectionTimeoutException e) {
                // A lower-priority getter's answer could be the wrong one: the whole detection is dropped
                DetectionTrace.provider(provider, "timeout", e.getMessage(), null);
                traceNotAsked(configs, i + 1);
                stopSample(sample, "timeout");
                throw e;
            } catch (Exception e) {
                log.warn("Getter {} failed for user {}, trying next: {}", config.getProvider(), user.getId(), e.getMessage());
                DetectionTrace.provider(provider, "error", String.valueOf(e.getMessage()), null);
            }
        }

        stopSample(sample, "none");
        return Optional.empty();
    }

    /** Notes the providers from {@code from} on as not asked, the chain having stopped before them. */
    private static void traceNotAsked(List<GetterConfig> configs, int from) {
        if (!DetectionTrace.active()) {
            return;
        }
        for (GetterConfig config : configs.subList(from, configs.size())) {
            DetectionTrace.provider(config.getProvider().name(), "not-asked", null, null);
        }
    }

    private void stopSample(Timer.Sample sample, String resolvedBy) {
        sample.stop(Timer.builder("catapult.game.detection.duration")
                .description("Duration of the game detection chain")
                .tag("resolved_by", resolvedBy)
                .register(meterRegistry));
    }

    private Map<GetterConfig.Provider, GameGetter> buildGetterMap() {
        Map<GetterConfig.Provider, GameGetter> map = new EnumMap<>(GetterConfig.Provider.class);
        steamGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.STEAM, g));
        xboxGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.XBOX, g));
        battleNetGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.BATTLENET, g));
        minecraftPresenceGetter.ifPresent(g -> map.put(GetterConfig.Provider.MINECRAFT, g));
        ankamaGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.ANKAMA, g));
        epicGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.EPIC, g));
        return map;
    }
}
