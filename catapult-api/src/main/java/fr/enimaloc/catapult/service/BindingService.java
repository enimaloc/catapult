package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BindingService {

    private final GameBindingRepository gameBindingRepository;
    private final IgdbService igdbService;
    private final TwitchService twitchService;
    private final TwResolverService twResolverService;

    @Transactional
    public GameBinding resolveOrCreate(UserAccount user, DetectedGame detectedGame) {
        Optional<GameBinding> existing = gameBindingRepository.findByUserAndSourceIdAndSourceType(
                        user, detectedGame.getSourceId(), detectedGame.getSourceType()
                );

        if (existing.isPresent() && existing.get().getStatus() == GameBinding.Status.INCOMPLETE) {
            existing = existing.map(binding -> updateWithIgdbResolution(user, detectedGame, binding));
        }

        return existing.orElseGet(() -> createWithIgdbResolution(user, detectedGame));
    }

    @Transactional
    public void refreshIncompleteBindings() {
        List<GameBinding> incomplete = gameBindingRepository.findAllByStatusAndIgnoredFalse(GameBinding.Status.INCOMPLETE);
        if (incomplete.isEmpty()) {
            log.info("No INCOMPLETE bindings to refresh");
            return;
        }
        log.info("Refreshing {} INCOMPLETE binding(s)", incomplete.size());
        int resolved = 0;
        for (GameBinding binding : incomplete) {
            DetectedGame detected = new DetectedGame(
                binding.getSourceId(), binding.getSourceType(), binding.getSourceName()
            );
            GameBinding updated = updateWithIgdbResolution(binding.getUser(), detected, binding);
            if (updated.getStatus() != GameBinding.Status.INCOMPLETE) resolved++;
        }
        log.info("INCOMPLETE binding refresh complete: {}/{} resolved", resolved, incomplete.size());
    }

    private GameBinding createWithIgdbResolution(UserAccount user, DetectedGame detectedGame) {
        GameBinding binding = new GameBinding();
        binding.setUser(user);
        binding.setSourceId(detectedGame.getSourceId());
        binding.setSourceType(detectedGame.getSourceType());
        binding.setSourceName(detectedGame.getSourceName());

        log.info("Creating binding for user {}  — game '{}'", user.getId(), detectedGame.getSourceName());
        return updateWithIgdbResolution(user, detectedGame, binding);
    }

    private GameBinding updateWithIgdbResolution(UserAccount user, DetectedGame detectedGame, GameBinding binding) {
        Optional<IgdbService.IgdbGame> igdbGame = resolveViaIgdb(detectedGame);

        if (igdbGame.isPresent()) {
            String igdbId = igdbGame.get().id();
            String gameName = igdbGame.get().name();
            String twitchId = igdbService.findTwitchGameId(igdbId)
                    .or(() -> twitchService.findCategoryIdByName(user, gameName))
                    .orElse(null);
            binding.setTwitchGameId(twitchId);
            binding.setTwitchGameName(gameName);
            binding.setStatus(GameBinding.Status.AUTO);

            Set<String> ccls = igdbService.suggestCcls(igdbId);
            binding.getCcls().clear();
            binding.getCcls().addAll(ccls);

            if (!binding.isTwOverride()) {
                Set<Long> descriptorIds = igdbService.fetchDescriptorIds(igdbId);
                String steamAppId = detectedGame.getSourceType() == GameBinding.SourceType.STEAM
                        ? detectedGame.getSourceId() : null;
                Set<String> tws = twResolverService.suggest(new TwResolverService.SuggestInput(
                        igdbId, descriptorIds, steamAppId, detectedGame.getSourceName()));
                binding.getTws().clear();
                binding.getTws().addAll(tws);
            }

            if (twitchId == null) {
                binding.setStatus(GameBinding.Status.INCOMPLETE);
                log.info("Binding updated as INCOMPLETE for user {} — game '{}' found on IGDB, but no twitch id found",
                        user.getId(), detectedGame.getSourceName());
            } else log.info("Binding updated for user {} — game '{}' resolved to IGDB '{}' (twitchId={})",
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
    public void updateBinding(UserAccount user, UUID bindingId, String twitchGameId,
                              String twitchGameName, Set<String> ccls, boolean ignored) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding ->
            updateBinding(user, bindingId, twitchGameId, twitchGameName, ccls, binding.getTws(), ignored)
        );
    }

    @Transactional
    public void updateBinding(UserAccount user, UUID bindingId, String twitchGameId,
                              String twitchGameName, Set<String> ccls, Set<String> tws, boolean ignored) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.setTwitchGameId(twitchGameId);
            binding.setTwitchGameName(twitchGameName);
            binding.getCcls().clear();
            binding.getCcls().addAll(ccls);
            binding.getTws().clear();
            binding.getTws().addAll(tws);
            binding.setTwOverride(true);
            binding.setIgnored(ignored);
            if (twitchGameId != null && !twitchGameId.isBlank()) {
                binding.setStatus(GameBinding.Status.MANUAL);
            }
            gameBindingRepository.save(binding);
            twitchService.updateChannel(user, binding);
        });
    }

    @Transactional
    public void toggleCclEnabled(UserAccount user, UUID bindingId, boolean enabled) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.setCclEnabled(enabled);
            gameBindingRepository.save(binding);
            twitchService.updateChannel(user, binding);
        });
    }

    @Transactional
    public void toggleTwEnabled(UserAccount user, UUID bindingId, boolean enabled) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.setTwEnabled(enabled);
            gameBindingRepository.save(binding);
            twitchService.updateChannel(user, binding);
        });
    }

    @Transactional
    public void resetTws(UserAccount user, UUID bindingId) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.setTwOverride(false);
            DetectedGame detected = new DetectedGame(
                binding.getSourceId(), binding.getSourceType(), binding.getSourceName());
            updateWithIgdbResolution(user, detected, binding);
        });
    }

    @Transactional
    public void setTwsForBinding(UserAccount user, UUID bindingId, Set<String> tws) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.getTws().clear();
            binding.getTws().addAll(tws);
            binding.setTwOverride(true);
            gameBindingRepository.save(binding);
            twitchService.updateChannel(user, binding);
        });
    }

    public Set<String> previewTws(UserAccount user, UUID bindingId) {
        return gameBindingRepository.findByIdAndUser(bindingId, user)
            .flatMap(binding -> igdbService.resolveIgdbIdForBinding(binding)
                .map(igdbId -> {
                    Set<Long> descriptorIds = igdbService.fetchDescriptorIds(igdbId);
                    String steamAppId = binding.getSourceType() == GameBinding.SourceType.STEAM
                            ? binding.getSourceId() : null;
                    return twResolverService.suggest(new TwResolverService.SuggestInput(
                            igdbId, descriptorIds, steamAppId, binding.getSourceName()));
                }))
            .orElse(Set.of());
    }

    @Transactional
    public void toggleIgnored(UserAccount user, UUID bindingId, boolean ignored) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.setIgnored(ignored);
            gameBindingRepository.save(binding);
            twitchService.updateChannel(user, binding);
        });
    }

    @Transactional
    public void deleteBinding(UserAccount user, UUID bindingId) {
        gameBindingRepository.findByIdAndUser(bindingId, user)
            .ifPresent(gameBindingRepository::delete);
    }
}
