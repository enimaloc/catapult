package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class BindingService {

    private final GameBindingRepository gameBindingRepository;
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final TwitchService twitchService;
    private final TwResolverService twResolverService;
    private final GameStateService gameStateService;
    private final GameMappingRepository gameMappingRepository;

    private boolean isActiveBinding(UserAccount user, GameBinding binding) {
        return gameStateService.getLastKnownGame(user)
                .map(active -> active.getSourceType() == binding.getSourceType()
                        && active.getSourceId().equals(binding.getSourceId()))
                .orElse(false);
    }

    /**
     * Applies {@code change} to the user's binding (if it exists), saves it, and pushes it to
     * Twitch right away when it is the game currently being played.
     */
    private void editBinding(UserAccount user, UUID bindingId, Consumer<GameBinding> change) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            change.accept(binding);
            gameBindingRepository.save(binding);
            if (isActiveBinding(user, binding)) {
                twitchService.updateChannel(user, binding);
            }
        });
    }

    /** The TWs suggested for a game from its IGDB descriptors, Steam signals (Steam games only) and name. */
    private Set<String> suggestTws(String igdbId, DetectedGame game) {
        Set<Long> descriptorIds = igdbService.fetchDescriptorIds(igdbId);
        String steamAppId = game.getSourceType() == GameBinding.SourceType.STEAM ? game.getSourceId() : null;
        return twResolverService.suggest(new TwResolverService.SuggestInput(
                igdbId, descriptorIds, steamAppId, game.getSourceName()));
    }

    private static DetectedGame detectedOf(GameBinding binding) {
        return new DetectedGame(binding.getSourceId(), binding.getSourceType(), binding.getSourceName());
    }

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
            GameBinding updated = DebugContext.callAs(binding.getUser().getId(),
                    () -> updateWithIgdbResolution(binding.getUser(), detectedOf(binding), binding));
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

    /**
     * Resolves the binding's category and saves it: from the admin game mapping of its game when there is one
     * (MAPPED), else from IGDB (AUTO, or INCOMPLETE when IGDB finds no Twitch category).
     */
    private GameBinding updateWithIgdbResolution(UserAccount user, DetectedGame detectedGame, GameBinding binding) {
        Optional<GameMapping> mapping = detectedGame.getSourceId() != null
                ? gameMappingRepository.findBySourceTypeAndSourceId(
                        detectedGame.getSourceType(), detectedGame.getSourceId())
                : Optional.empty();
        if (mapping.isPresent()) {
            applyMapping(binding, mapping.get());
            log.info("Binding updated as MAPPED for user {} — game '{}' mapped by an admin to '{}' (twitchId={})",
                    user.getId(), detectedGame.getSourceName(), binding.getTwitchGameName(), binding.getTwitchGameId());
            return gameBindingRepository.save(binding);
        }
        return updateWithoutMapping(user, detectedGame, binding);
    }

    /**
     * Applies an admin game mapping to a binding of its game, without saving it: MAPPED, the mapping's category,
     * the admin's CCLs/TWs when the mapping replaces them (else IGDB's), the streamer's TWs when pinned
     * ({@code twOverride}). The streamer's toggles (ignored, cclEnabled, twEnabled) stay as they are.
     *
     * @return whether the binding changed; always false for a MANUAL binding, which is never touched.
     */
    public boolean applyMapping(GameBinding binding, GameMapping mapping) {
        if (binding.getStatus() == GameBinding.Status.MANUAL) return false;
        boolean keepsTws = binding.isTwOverride() || mapping.isTwsOverridden();
        return applyMapping(binding, mapping,
                igdbSuggestions(detectedOf(binding), !mapping.isCclsOverridden(), !keepsTws));
    }

    /** IGDB's CCLs and TWs for a game, resolved once for all its bindings (empty when IGDB does not know it). */
    public record MappingSuggestions(Set<String> ccls, Set<String> tws) {
        public static final MappingSuggestions NONE = new MappingSuggestions(Set.of(), Set.of());
    }

    /**
     * IGDB's CCL and/or TW suggestions for a detected game (by its app ID, else its name when given), for
     * {@link #applyMapping(GameBinding, GameMapping, MappingSuggestions)}: resolved before the transaction that
     * applies a mapping to all the game's bindings, so their rows are not locked during IGDB calls. Looks IGDB up
     * only when one of them is wanted; the unwanted one is empty.
     */
    public MappingSuggestions igdbSuggestions(DetectedGame game, boolean ccls, boolean tws) {
        if (!ccls && !tws) return MappingSuggestions.NONE;
        Optional<String> igdbId = (game.getSourceName() != null ? resolveViaIgdb(game)
                : game.getSourceId() != null
                        ? igdbService.findByExternalAppId(game.getSourceType(), game.getSourceId())
                        : Optional.<IgdbService.IgdbGame>empty())
                .map(IgdbService.IgdbGame::id);
        return new MappingSuggestions(
                ccls ? igdbId.map(igdbService::suggestCcls).orElse(Set.of()) : Set.of(),
                tws ? igdbId.map(id -> suggestTws(id, game)).orElse(Set.of()) : Set.of());
    }

    /**
     * {@link #applyMapping(GameBinding, GameMapping)} with IGDB's suggestions already resolved: they serve the CCLs
     * and TWs the mapping does not replace (and the TWs the streamer did not pin). Does not save.
     *
     * @return whether the binding changed; always false for a MANUAL binding, which is never touched.
     */
    public boolean applyMapping(GameBinding binding, GameMapping mapping, MappingSuggestions suggestions) {
        if (binding.getStatus() == GameBinding.Status.MANUAL) return false;

        Set<String> ccls = mapping.isCclsOverridden() ? Set.copyOf(mapping.getCcls()) : suggestions.ccls();
        Set<String> tws = binding.isTwOverride() ? Set.copyOf(binding.getTws())
                : mapping.isTwsOverridden() ? Set.copyOf(mapping.getTws())
                : suggestions.tws();

        boolean changed = binding.getStatus() != GameBinding.Status.MAPPED
                || !Objects.equals(binding.getTwitchGameId(), mapping.getTwitchGameId())
                || !Objects.equals(binding.getTwitchGameName(), mapping.getTwitchGameName())
                || !binding.getCcls().equals(ccls)
                || !binding.getTws().equals(tws);

        binding.setStatus(GameBinding.Status.MAPPED);
        binding.setTwitchGameId(mapping.getTwitchGameId());
        binding.setTwitchGameName(mapping.getTwitchGameName());
        binding.getCcls().clear();
        binding.getCcls().addAll(ccls);
        binding.getTws().clear();
        binding.getTws().addAll(tws);
        return changed;
    }

    /** Resolves and saves the binding from IGDB alone, its game's mapping ignored — for a deleted mapping. */
    @Transactional
    public GameBinding resolveWithoutMapping(GameBinding binding) {
        return updateWithoutMapping(binding.getUser(), detectedOf(binding), binding);
    }

    private GameBinding updateWithoutMapping(UserAccount user, DetectedGame detectedGame, GameBinding binding) {
        Optional<IgdbService.IgdbGame> igdbGame = resolveViaIgdb(detectedGame);

        if (igdbGame.isPresent()) {
            String igdbId = igdbGame.get().id();
            String gameName = igdbGame.get().name();
            String twitchId = igdbGameDetailsService.getDetails(igdbId)
                    .map(IgdbGameDetails::getWebsites)
                    .map(websites -> websites.get("twitch"))
                    .filter(id -> !id.isBlank())
                    .or(() -> igdbService.findTwitchGameId(igdbId))
                    .or(() -> twitchService.findCategoryIdByName(user, gameName))
                    .orElse(null);
            binding.setTwitchGameId(twitchId);
            binding.setTwitchGameName(gameName);
            binding.setStatus(GameBinding.Status.AUTO);

            Set<String> ccls = igdbService.suggestCcls(igdbId);
            binding.getCcls().clear();
            binding.getCcls().addAll(ccls);

            if (!binding.isTwOverride()) {
                Set<String> tws = suggestTws(igdbId, detectedGame);
                log.debug("[TW] mapping for user {} game '{}' (igdb={}): {}",
                        user.getId(), detectedGame.getSourceName(), igdbId, tws);
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
        return detectedGame.getSourceId() != null
                ? igdbService.findByExternalAppId(detectedGame.getSourceType(), detectedGame.getSourceId())
                    .or(() -> igdbService.findByName(detectedGame.getSourceName()))
                : Optional.empty();
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
        editBinding(user, bindingId, binding -> {
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
        });
    }

    @Transactional
    public void setTwitchGame(UserAccount user, UUID bindingId, String twitchGameId, String twitchGameName) {
        editBinding(user, bindingId, binding -> {
            binding.setTwitchGameId(twitchGameId);
            binding.setTwitchGameName(twitchGameName);
            binding.setStatus(GameBinding.Status.MANUAL);
        });
    }

    @Transactional
    public void toggleCclEnabled(UserAccount user, UUID bindingId, boolean enabled) {
        editBinding(user, bindingId, binding -> binding.setCclEnabled(enabled));
    }

    @Transactional
    public void toggleTwEnabled(UserAccount user, UUID bindingId, boolean enabled) {
        editBinding(user, bindingId, binding -> binding.setTwEnabled(enabled));
    }

    @Transactional
    public void resetTws(UserAccount user, UUID bindingId) {
        gameBindingRepository.findByIdAndUser(bindingId, user).ifPresent(binding -> {
            binding.setTwOverride(false);
            // Resetting the TWs hands a MANUAL binding back to the automatic resolution: its game's admin mapping
            // when there is one, else IGDB (which sets the status itself).
            if (binding.getStatus() == GameBinding.Status.MANUAL) binding.setStatus(GameBinding.Status.AUTO);
            updateWithIgdbResolution(user, detectedOf(binding), binding);
        });
    }

    @Transactional
    public void setTwsForBinding(UserAccount user, UUID bindingId, Set<String> tws) {
        editBinding(user, bindingId, binding -> {
            binding.getTws().clear();
            binding.getTws().addAll(tws);
            binding.setTwOverride(true);
        });
    }

    public Set<String> previewTws(UserAccount user, UUID bindingId) {
        return gameBindingRepository.findByIdAndUser(bindingId, user)
                .flatMap(binding -> igdbService.resolveIgdbIdForBinding(binding)
                        .map(igdbId -> suggestTws(igdbId, detectedOf(binding))))
                .orElse(Set.of());
    }

    @Transactional
    public void toggleIgnored(UserAccount user, UUID bindingId, boolean ignored) {
        editBinding(user, bindingId, binding -> binding.setIgnored(ignored));
    }

    @Transactional
    public void deleteBinding(UserAccount user, UUID bindingId) {
        gameBindingRepository.findByIdAndUser(bindingId, user)
                .ifPresent(gameBindingRepository::delete);
    }

    public Optional<GameBinding> findBinding(UserAccount user, UUID bindingId) {
        return gameBindingRepository.findByIdAndUser(bindingId, user);
    }

    /**
     * Read-only counterpart to {@link #resolveOrCreate}: the existing binding for a detected
     * game, or empty when there is none. Creates and mutates nothing, so callers that merely
     * describe the current state (notifications, read views) can't cause a write.
     */
    public Optional<GameBinding> findBinding(UserAccount user, DetectedGame detectedGame) {
        return gameBindingRepository.findByUserAndSourceIdAndSourceType(
            user, detectedGame.getSourceId(), detectedGame.getSourceType());
    }
}
