package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
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
 * Orchestre la chaîne de game getters selon l'ordre de priorité de l'utilisateur.
 * S'arrête au premier getter retournant un résultat.
 * En cas d'erreur sur un getter, passe au suivant sans interrompre le cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameGetterChain {

    private final GetterConfigRepository getterConfigRepository;
    private final Optional<SteamGameGetter> steamGameGetter;
    private final Optional<XboxGameGetter> xboxGameGetter;
    private final Optional<BattleNetGameGetter> battleNetGameGetter;
    private final MeterRegistry meterRegistry;

    public Optional<DetectedGame> resolve(UserAccount user) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Map<GetterConfig.Provider, GameGetter> getterByProvider = buildGetterMap();

        List<GetterConfig> configs = getterConfigRepository.findByUserOrderByPriorityAsc(user);

        for (GetterConfig config : configs) {
            GameGetter getter = getterByProvider.get(config.getProvider());
            if (!config.isEnabled() || getter == null) continue;

            try {
                Optional<DetectedGame> result = getter.getCurrentGame(user);
                if (result.isPresent()) {
                    log.debug("Game detected for user {} via {}: {}", user.getId(), config.getProvider(), result.get().getSourceName());
                    stopSample(sample, config.getProvider().name().toLowerCase());
                    return result;
                }
            } catch (Exception e) {
                log.warn("Getter {} failed for user {}, trying next: {}", config.getProvider(), user.getId(), e.getMessage());
            }
        }

        stopSample(sample, "none");
        return Optional.empty();
    }

    private void stopSample(Timer.Sample sample, String resolvedBy) {
        sample.stop(Timer.builder("catapult.game.detection.duration")
                .description("Durée de la chaîne de détection de jeu")
                .tag("resolved_by", resolvedBy)
                .register(meterRegistry));
    }

    private Map<GetterConfig.Provider, GameGetter> buildGetterMap() {
        Map<GetterConfig.Provider, GameGetter> map = new EnumMap<>(GetterConfig.Provider.class);
        steamGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.STEAM, g));
        xboxGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.XBOX, g));
        battleNetGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.BATTLENET, g));
        return map;
    }
}
