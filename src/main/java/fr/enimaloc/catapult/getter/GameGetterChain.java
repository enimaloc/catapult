package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
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
    private final Optional<ObsGameGetter> obsGameGetter;

    public Optional<DetectedGame> resolve(UserAccount user) {
        Map<GetterConfig.Provider, GameGetter> getterByProvider = buildGetterMap();

        List<GetterConfig> configs = getterConfigRepository.findByUserOrderByPriorityAsc(user);

        for (GetterConfig config : configs) {
            if (!config.isEnabled()) continue;

            GameGetter getter = getterByProvider.get(config.getProvider());
            if (getter == null) continue;

            try {
                Optional<DetectedGame> result = getter.getCurrentGame(user);
                if (result.isPresent()) {
                    log.debug("Game detected for user {} via {}: {}", user.getId(), config.getProvider(), result.get().getSourceName());
                    return result;
                }
            } catch (Exception e) {
                log.warn("Getter {} failed for user {}, trying next: {}", config.getProvider(), user.getId(), e.getMessage());
            }
        }

        return Optional.empty();
    }

    private Map<GetterConfig.Provider, GameGetter> buildGetterMap() {
        Map<GetterConfig.Provider, GameGetter> map = new EnumMap<>(GetterConfig.Provider.class);
        steamGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.STEAM, g));
        xboxGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.XBOX, g));
        battleNetGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.BATTLENET, g));
        obsGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.OBS, g));
        return map;
    }
}
