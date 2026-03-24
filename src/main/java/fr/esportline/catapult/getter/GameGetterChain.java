package fr.esportline.catapult.getter;

import fr.esportline.catapult.domain.GetterConfig;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.GetterConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final SteamGameGetter steamGameGetter;

    public Optional<DetectedGame> resolve(UserAccount user) {
        Map<GetterConfig.Provider, GameGetter> getterByProvider = Map.of(
            GetterConfig.Provider.STEAM, steamGameGetter
        );

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
}
