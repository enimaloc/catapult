package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The bindings a streamer creates and triggers by hand, tied to no game (« Art », « Développement »…), behind the
 * {@value #EXPERIMENT_KEY} experiment: outside it every operation is a 403 and an activation left over counts as none.
 *
 * <p>Triggering one applies its category straight to Twitch, live or not, through a transient {@link GameBinding}
 * like {@link GameEventListener}'s fallbacks; only a change Twitch accepted is recorded as the active one (at most
 * one per streamer, triggering another replaces it). Deactivating behaves like a detection of the game open at that
 * moment: live, it takes over (or the usual "no game" category); offline, it goes pending and the channel gets the
 * streamer's end-of-stream choice.</p>
 *
 * <p>Twitch is never called inside a transaction: each repository call commits on its own.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomBindingService {

    public static final String EXPERIMENT_KEY = "custom.bindings";
    public static final int MAX_PER_STREAMER = 20;
    public static final int MAX_NAME_LENGTH = 40;
    static final int MAX_TW_LENGTH = 40;
    /** {@link GameBinding#getSourceName()} prefix of the transient binding a custom binding is applied through. */
    public static final String SOURCE_NAME_PREFIX = "custom:";

    public enum ActivationResult {
        /** Twitch accepted the category: the binding is now the active one. */
        APPLIED,
        /** Nothing could be sent: no Twitch token, a refused one (the bot is then paused), or the bot is off. */
        TWITCH_UNAVAILABLE,
        /** Twitch failed or refused the update. */
        FAILED
    }

    private final CustomBindingRepository bindings;
    private final CustomBindingActivationRepository activations;
    private final TwitchService twitchService;
    private final ExperimentService experimentService;
    private final GameStateService gameStateService;
    private final StreamStateService streamStateService;
    private final UserSettingsRepository userSettingsRepository;
    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final GameEventListener gameEventListener;
    private final ChannelEventPublisher channelEventPublisher;

    /** Created DRAFT, so closed to everyone until the admin opens it. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureExperiment() {
        experimentService.ensureExists(EXPERIMENT_KEY, "enabled");
    }

    public boolean enabled(UserAccount user) {
        return experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    public List<CustomBinding> list(UserAccount user) {
        requireEnabled(user);
        return bindings.findByUserOrderByCreatedAtAsc(user);
    }

    public CustomBinding create(UserAccount user, CustomBindingRequest request) {
        requireEnabled(user);
        String name = validName(request);
        validCategory(request);
        if (bindings.countByUser(user) >= MAX_PER_STREAMER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "At most " + MAX_PER_STREAMER + " custom bindings per channel");
        }
        if (bindings.findByUserAndNameIgnoreCase(user, name).isPresent()) {
            throw nameTaken();
        }
        CustomBinding binding = new CustomBinding();
        binding.setUser(user);
        CustomBinding saved = save(fill(binding, name, request));
        channelEventPublisher.customBindingsUpdated(user.getId());
        return saved;
    }

    /** Saved only: an active binding's new category reaches Twitch on its next activation. */
    public CustomBinding update(UserAccount user, UUID id, CustomBindingRequest request) {
        requireEnabled(user);
        CustomBinding binding = existing(user, id);
        String name = validName(request);
        validCategory(request);
        bindings.findByUserAndNameIgnoreCase(user, name)
                .filter(other -> !other.getId().equals(binding.getId()))
                .ifPresent(other -> {
                    throw nameTaken();
                });
        CustomBinding saved = save(fill(binding, name, request));
        channelEventPublisher.customBindingsUpdated(user.getId());
        return saved;
    }

    /** Deleting the active one deactivates it first, with the same effect as a deactivation. */
    public void delete(UserAccount user, UUID id) {
        requireEnabled(user);
        CustomBinding binding = existing(user, id);
        activations.findById(user.getId())
                .filter(activation -> activation.getCustomBinding().getId().equals(binding.getId()))
                .ifPresent(activation -> doDeactivate(user));
        bindings.delete(binding);
        channelEventPublisher.customBindingsUpdated(user.getId());
    }

    /** The streamer's binding with that id, or else that name whatever its case. */
    public Optional<CustomBinding> resolve(UserAccount user, String idOrName) {
        requireEnabled(user);
        if (idOrName == null || idOrName.isBlank()) return Optional.empty();
        String key = idOrName.trim();
        Optional<CustomBinding> byId = parseUuid(key).flatMap(id -> bindings.findByIdAndUser(id, user));
        return byId.isPresent() ? byId : bindings.findByUserAndNameIgnoreCase(user, key);
    }

    /**
     * Applies {@code binding} to the channel now, live or not, and records it as the active one once Twitch has
     * accepted it; re-triggering the active one applies it again. A failure records nothing.
     */
    public ActivationResult activate(UserAccount user, CustomBinding binding) {
        requireEnabled(user);
        if (binding.getUser() == null || !user.getId().equals(binding.getUser().getId())) {
            throw notFound();
        }
        ChannelUpdateOutcome outcome = twitchService.updateChannelReporting(user, toGameBinding(user, binding));
        ActivationResult result = switch (outcome) {
            case APPLIED -> ActivationResult.APPLIED;
            case SKIPPED -> ActivationResult.TWITCH_UNAVAILABLE;
            case FAILED -> twitchUnusable(user) ? ActivationResult.TWITCH_UNAVAILABLE : ActivationResult.FAILED;
        };
        if (result != ActivationResult.APPLIED) {
            log.info("Custom binding {} not activated for user {}: Twitch update {}", binding.getId(), user.getId(), outcome);
            return result;
        }
        activations.save(new CustomBindingActivation(user.getId(), binding, Instant.now()));
        channelEventPublisher.customBindingChanged(user.getId(), binding.getId());
        log.debug("Custom binding {} activated for user {}", binding.getId(), user.getId());
        return result;
    }

    /** Ends the active binding, if any, as if the game open now had just been detected. */
    public void deactivate(UserAccount user) {
        requireEnabled(user);
        if (activations.findById(user.getId()).isPresent()) {
            doDeactivate(user);
        }
    }

    /** The active custom binding; none while the streamer is outside the experiment. */
    public Optional<CustomBindingActivation> active(UserAccount user) {
        if (!enabled(user)) return Optional.empty();
        return activations.findById(user.getId());
    }

    private void doDeactivate(UserAccount user) {
        activations.deleteById(user.getId());
        channelEventPublisher.customBindingChanged(user.getId(), null);
        Optional<DetectedGame> open = gameStateService.getLastKnownGame(user);
        if (streamStateService.isLive(user)) {
            open.ifPresentOrElse(game -> gameEventListener.applyDetectedGame(user, game),
                    () -> gameEventListener.applyNoGameFallback(user));
            return;
        }
        // Offline, a detection only stores the game pending; the channel gets the end-of-stream choice
        open.ifPresent(game -> gameEventListener.applyDetectedGame(user, game));
        boolean applyDefaultOnStreamEnd = userSettingsRepository.findById(user.getId())
                .map(UserSettings::isApplyDefaultOnStreamEnd)
                .orElse(false);
        if (applyDefaultOnStreamEnd) {
            twitchService.resetToDefault(user);
        }
    }

    /** After a failed update: whether the cause is the Twitch link (no token, or one refused, which pauses the bot). */
    private boolean twitchUnusable(UserAccount user) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH).isEmpty()
                || userAccountRepository.findById(user.getId()).map(account -> !account.isBotEnabled()).orElse(true);
    }

    static GameBinding toGameBinding(UserAccount user, CustomBinding binding) {
        GameBinding applied = new GameBinding();
        applied.setUser(user);
        applied.setSourceType(GameBinding.SourceType.MANUAL);
        applied.setStatus(GameBinding.Status.MANUAL);
        applied.setSourceName(SOURCE_NAME_PREFIX + binding.getName());
        applied.setTwitchGameId(binding.getTwitchGameId());
        applied.setTwitchGameName(binding.getTwitchGameName());
        applied.getCcls().addAll(binding.getCcls());
        applied.getTws().addAll(binding.getTws());
        return applied;
    }

    private void requireEnabled(UserAccount user) {
        if (!enabled(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled");
        }
    }

    private CustomBinding existing(UserAccount user, UUID id) {
        return bindings.findByIdAndUser(id, user).orElseThrow(CustomBindingService::notFound);
    }

    private CustomBinding save(CustomBinding binding) {
        try {
            return bindings.save(binding);
        } catch (DataIntegrityViolationException e) {
            // Two saves of the same name at once: the (user_id, lower(name)) index rejects the second
            throw nameTaken();
        }
    }

    private static CustomBinding fill(CustomBinding binding, String name, CustomBindingRequest request) {
        binding.setName(name);
        binding.setTwitchGameId(request.twitchGameId().trim());
        binding.setTwitchGameName(request.twitchGameName().trim());
        binding.setUninterruptible(request.uninterruptible());
        binding.getCcls().clear();
        binding.getCcls().addAll(cleaned(request.ccls()));
        Set<String> tws = cleaned(request.tws());
        if (tws.stream().anyMatch(tw -> tw.length() > MAX_TW_LENGTH)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tags are at most " + MAX_TW_LENGTH + " characters");
        }
        binding.getTws().clear();
        binding.getTws().addAll(tws);
        return binding;
    }

    private static String validName(CustomBindingRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    private static void validCategory(CustomBindingRequest request) {
        if (request.twitchGameId() == null || request.twitchGameId().isBlank()
                || request.twitchGameName() == null || request.twitchGameName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A Twitch category is required");
        }
    }

    private static Set<String> cleaned(Set<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value.trim());
        }
        return out;
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ResponseStatusException nameTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A custom binding already has that name");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown custom binding");
    }
}
