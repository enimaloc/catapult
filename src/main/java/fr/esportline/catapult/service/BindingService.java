package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.getter.DetectedGame;
import fr.esportline.catapult.repository.GameBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BindingService {

    private final GameBindingRepository gameBindingRepository;
    private final IgdbService igdbService;
    private final TwitchService twitchService;

    @Transactional
    public GameBinding resolveOrCreate(UserAccount user, DetectedGame detectedGame) {
        Optional<GameBinding> existing = gameBindingRepository.findByUserAndSourceIdAndSourceType(
            user, detectedGame.getSourceId(), detectedGame.getSourceType()
        );

        return existing.orElseGet(() -> createWithIgdbResolution(user, detectedGame));

    }

    private GameBinding createWithIgdbResolution(UserAccount user, DetectedGame detectedGame) {
        GameBinding binding = new GameBinding();
        binding.setUser(user);
        binding.setSourceId(detectedGame.getSourceId());
        binding.setSourceType(detectedGame.getSourceType());
        binding.setSourceName(detectedGame.getSourceName());

        Optional<IgdbService.IgdbGame> igdbGame = resolveViaIgdb(detectedGame);

        if (igdbGame.isPresent()) {
            String igdbId = igdbGame.get().id();
            String gameName = igdbGame.get().name();
            String twitchId = igdbService.findTwitchGameId(igdbId)
                .or(() -> twitchService.findCategoryIdByName(user, gameName))
                .orElse(igdbId);
            binding.setTwitchGameId(twitchId);
            binding.setTwitchGameName(gameName);
            binding.setStatus(GameBinding.Status.AUTO);

            var ccls = igdbService.suggestCcls(igdbId);
            binding.setCcls(ccls);

            log.info("Binding created for user {} — game '{}' resolved to IGDB '{}' (twitchId={})",
                user.getId(), detectedGame.getSourceName(), igdbGame.get().name(), twitchId);
        } else {
            binding.setStatus(GameBinding.Status.INCOMPLETE);
            log.info("Binding created as INCOMPLETE for user {} — game '{}' not found in IGDB",
                user.getId(), detectedGame.getSourceName());
        }

        return gameBindingRepository.save(binding);
    }

    private Optional<IgdbService.IgdbGame> resolveViaIgdb(DetectedGame detectedGame) {
        if (detectedGame.getSourceType() == GameBinding.SourceType.STEAM
            && detectedGame.getSourceId() != null) {
            Optional<IgdbService.IgdbGame> byAppId = igdbService.findBySteamAppId(detectedGame.getSourceId());
            if (byAppId.isPresent()) return byAppId;
        }

        return igdbService.findByName(detectedGame.getSourceName());
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
    public void toggleCclEnabled(UUID bindingId, boolean enabled) {
        gameBindingRepository.findById(bindingId).ifPresent(binding -> {
            binding.setCclEnabled(enabled);
            gameBindingRepository.save(binding);
        });
    }

    @Transactional
    public void toggleIgnored(UUID bindingId, boolean ignored) {
        gameBindingRepository.findById(bindingId).ifPresent(binding -> {
            binding.setIgnored(ignored);
            gameBindingRepository.save(binding);
        });
    }

    @Transactional
    public void deleteBinding(UUID bindingId) {
        gameBindingRepository.deleteById(bindingId);
    }
}
