package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.getter.DetectedGame;
import fr.esportline.catapult.repository.GameBindingRepository;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Gestion des bindings jeu ↔ catégorie Twitch.
 * Résolution automatique via IGDB si aucun binding existant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BindingService {

    private final GameBindingRepository gameBindingRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final IgdbService igdbService;
    private final TokenEncryptionService tokenEncryptionService;

    /**
     * Résout ou crée le binding pour un jeu détecté.
     * Si aucun binding n'existe, tente la résolution IGDB automatique.
     */
    @Transactional
    public GameBinding resolveOrCreate(UserAccount user, DetectedGame detectedGame) {
        Optional<GameBinding> existing = gameBindingRepository.findByUserAndSourceIdAndSourceType(
            user, detectedGame.getSourceId(), detectedGame.getSourceType()
        );

        if (existing.isPresent()) {
            return existing.get();
        }

        return createWithIgdbResolution(user, detectedGame);
    }

    private GameBinding createWithIgdbResolution(UserAccount user, DetectedGame detectedGame) {
        GameBinding binding = new GameBinding();
        binding.setUser(user);
        binding.setSourceId(detectedGame.getSourceId());
        binding.setSourceType(detectedGame.getSourceType());
        binding.setSourceName(detectedGame.getSourceName());

        String twitchAccessToken = getTwitchAccessToken(user);

        // Résolution IGDB
        Optional<IgdbService.IgdbGame> igdbGame = resolveViaIgdb(detectedGame, twitchAccessToken);

        if (igdbGame.isPresent()) {
            binding.setTwitchGameId(igdbGame.get().id());
            binding.setTwitchGameName(igdbGame.get().name());
            binding.setStatus(GameBinding.Status.AUTO);

            // Suggestion CCLs
            var ccls = igdbService.suggestCcls(igdbGame.get().id(), twitchAccessToken);
            binding.setCcls(ccls);

            log.info("Binding created for user {} — game '{}' resolved to IGDB '{}'",
                user.getId(), detectedGame.getSourceName(), igdbGame.get().name());
        } else {
            binding.setStatus(GameBinding.Status.INCOMPLETE);
            log.info("Binding created as INCOMPLETE for user {} — game '{}' not found in IGDB",
                user.getId(), detectedGame.getSourceName());
        }

        return gameBindingRepository.save(binding);
    }

    private Optional<IgdbService.IgdbGame> resolveViaIgdb(DetectedGame detectedGame, String twitchAccessToken) {
        // Étape 1 : recherche par external_games (Steam uniquement)
        if (detectedGame.getSourceType() == fr.esportline.catapult.domain.GameBinding.SourceType.STEAM
            && detectedGame.getSourceId() != null) {
            Optional<IgdbService.IgdbGame> byAppId = igdbService.findBySteamAppId(detectedGame.getSourceId(), twitchAccessToken);
            if (byAppId.isPresent()) return byAppId;
        }

        // Étape 2 : fallback textuel par nom
        return igdbService.findByName(detectedGame.getSourceName(), twitchAccessToken);
    }

    private String getTwitchAccessToken(UserAccount user) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .map(t -> tokenEncryptionService.decrypt(t.getAccessToken()))
            .orElse("");
    }

    @Transactional
    public void updateBinding(UUID bindingId, String twitchGameId, String twitchGameName,
                              java.util.Set<fr.esportline.catapult.domain.TwitchCcl> ccls, boolean ignored) {
        gameBindingRepository.findById(bindingId).ifPresent(binding -> {
            binding.setTwitchGameId(twitchGameId);
            binding.setTwitchGameName(twitchGameName);
            binding.setCcls(ccls);
            binding.setIgnored(ignored);
            if (twitchGameId != null && !twitchGameId.isBlank()) {
                binding.setStatus(GameBinding.Status.MANUAL);
            }
            gameBindingRepository.save(binding);
        });
    }

    @Transactional
    public void deleteBinding(UUID bindingId) {
        gameBindingRepository.deleteById(bindingId);
    }
}
