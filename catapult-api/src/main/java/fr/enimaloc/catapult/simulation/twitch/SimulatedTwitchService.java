package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.ComponentPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.OverlayPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.PanelPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.Slot;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@link TwitchService} with each Helix call in the mode of the channel it acts on. {@code REAL} hands the whole
 * method to {@link TwitchServiceImpl}; otherwise nothing leaves, and the answer and failures follow the real
 * client's contract, method by method:
 * <ul>
 *   <li>the category PATCHes ({@code updateChannelReporting}, {@code setCategory}, {@code resetToDefault}) skip a
 *   disabled bot or an inactive account first, then on a 401 refresh the token (simulated too), retry once and pause
 *   the bot when either fails; {@code setCategory} throws what failed, the two others answer {@code FAILED}. Once
 *   applied, the channel's category and labels are kept in the simulated data;</li>
 *   <li>the reads answer the simulated data, or their real fallback (empty) on a failure;</li>
 *   <li>{@code setActiveExtensions} refuses a disabled bot or an inactive account, answers 400 to an extension the
 *   simulated data does not list as installed, and throws a failure as is;</li>
 *   <li>the category search is the global one ({@link SimulatedTwitchCategoryService});</li>
 *   <li>a category change Twitch applied (simulated or real) is followed by its {@code channel.update} echo,
 *   see {@link ChannelUpdateEcho}.</li>
 * </ul>
 * The simulated path needs no stored token: a fictitious account has none.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedTwitchService implements TwitchService {

    /** The labels a broadcaster can set, as {@code TwitchServiceImpl} sends them. */
    private static final Set<String> EDITABLE_CCL_IDS =
            Set.of("ViolentGraphic", "SexualThemes", "DrugsIntoxication", "Gambling", "ProfanityVulgarity");

    private final ObjectProvider<TwitchServiceImpl> real;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;
    private final SimulatedTwitchCategoryService categories;
    private final SimulatedTwitchTokenService tokens;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final BotToggleService botToggleService;
    private final CatapultCategoryChangeStateService categoryChangeStateService;
    private final ChannelUpdateEcho echo;

    @Override
    public ChannelUpdateOutcome updateChannelReporting(UserAccount user, GameBinding binding) {
        if (!canActOnTwitch(user)
                || binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
            return ChannelUpdateOutcome.SKIPPED;
        }
        Set<String> ccls = cclsSent(user, binding);
        return echoed(user, binding.getTwitchGameId(), binding.getTwitchGameName(), ccls, () -> unwrapped(() -> {
            try {
                return patch(user, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL,
                        "PATCH /channels game_id=" + binding.getTwitchGameId(),
                        () -> real().updateChannelReporting(user, binding),
                        () -> applied(user, binding.getTwitchGameId(), binding.getTwitchGameName(), ccls));
            } catch (RestClientException e) {
                log.warn("Twitch channel update failed for user {}: {}", user.getId(), e.getMessage());
                return ChannelUpdateOutcome.FAILED;
            }
        }));
    }

    @Override
    public ChannelUpdateOutcome setCategory(UserAccount user, String twitchGameId, String twitchGameName) {
        if (!canActOnTwitch(user)) {
            return ChannelUpdateOutcome.SKIPPED;
        }
        return echoed(user, twitchGameId, twitchGameName, null,
                () -> unwrapped(() -> patch(user, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY,
                        "PATCH /channels game_id=" + twitchGameId,
                        () -> real().setCategory(user, twitchGameId, twitchGameName),
                        () -> applied(user, twitchGameId, twitchGameName, null))));
    }

    @Override
    public ChannelUpdateOutcome resetToDefault(UserAccount user) {
        if (!canActOnTwitch(user)) {
            return ChannelUpdateOutcome.SKIPPED;
        }
        UserSettings settings = userSettingsRepository.findById(user.getId()).orElse(null);
        if (settings == null || settings.getNoGameTwitchGameId() == null
                || settings.getNoGameTwitchGameId().isBlank()) {
            return ChannelUpdateOutcome.SKIPPED;
        }
        Set<String> ccls = settings.isCclFeatureEnabled() && !settings.getNoGameCcls().isEmpty()
                ? editable(settings.getNoGameCcls())
                : null;
        return echoed(user, settings.getNoGameTwitchGameId(), settings.getNoGameTwitchGameName(), ccls,
                () -> unwrapped(() -> {
                    try {
                        return patch(user, SimulatedCalls.TWITCH_HELIX_RESET_TO_DEFAULT,
                                "PATCH /channels game_id=" + settings.getNoGameTwitchGameId(),
                                () -> real().resetToDefault(user),
                                () -> applied(user, settings.getNoGameTwitchGameId(),
                                        settings.getNoGameTwitchGameName(), ccls));
                    } catch (RestClientException e) {
                        log.warn("Twitch channel reset failed for user {}: {}", user.getId(), e.getMessage());
                        return ChannelUpdateOutcome.FAILED;
                    }
                }));
    }

    /**
     * Runs a category change and, once Twitch has {@code APPLIED} it (simulated or real), has Twitch's
     * {@code channel.update} echo follow, as {@link ChannelUpdateEcho} decides.
     */
    private ChannelUpdateOutcome echoed(UserAccount user, String gameId, String gameName, @Nullable Set<String> ccls,
                                        Supplier<ChannelUpdateOutcome> change) {
        echo.prepare(user);
        ChannelUpdateOutcome outcome = change.get();
        if (outcome == ChannelUpdateOutcome.APPLIED) {
            echo.echo(user, gameId, gameName, ccls);
        }
        return outcome;
    }

    /** Falls back on the global category search when the exact match fails, as the real lookup does. */
    @Override
    public Optional<String> findCategoryIdByName(UserAccount user, String gameName) {
        return unwrapped(() -> {
            try {
                return call(user, SimulatedCalls.TWITCH_HELIX_FIND_CATEGORY, "GET /games?name=" + gameName,
                        () -> real().findCategoryIdByName(user, gameName),
                        () -> Optional.of(SimulatedTwitchCategoryService.categoryNamed(gameName).id()));
            } catch (RestClientException e) {
                log.warn("findCategoryIdByName '{}' — user-token exact match failed: {}", gameName, e.getMessage());
                return categories.searchCategories(gameName).stream()
                        .filter(category -> category.name().equalsIgnoreCase(gameName))
                        .findFirst()
                        .map(TwitchCategory::id);
            }
        });
    }

    @Override
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        return categories.searchCategories(query);
    }

    @Override
    public List<String> getModeratedChannelIds(UserAccount viewer) {
        return read(viewer, SimulatedCalls.TWITCH_HELIX_MODERATED_CHANNELS, "GET /moderation/channels", List.of(),
                () -> real().getModeratedChannelIds(viewer),
                () -> registry.data(viewer.getId()).moderatedChannelIds());
    }

    @Override
    public List<TwitchExtension> getUserExtensions(UserAccount user) {
        return read(user, SimulatedCalls.TWITCH_HELIX_USER_EXTENSIONS, "GET /users/extensions/list", List.of(),
                () -> real().getUserExtensions(user), () -> registry.data(user.getId()).installed());
    }

    @Override
    public TwitchExtensionPlacement getActiveExtensions(UserAccount user) {
        return read(user, SimulatedCalls.TWITCH_HELIX_ACTIVE_EXTENSIONS, "GET /users/extensions",
                TwitchExtensionPlacement.unreadable(), () -> real().getActiveExtensions(user),
                () -> registry.data(user.getId()).active());
    }

    @Override
    public TwitchExtensionPlacement setActiveExtensions(UserAccount user, TwitchExtensionPlacement placement) {
        if (!canActOnTwitch(user)) {
            throw new IllegalStateException(
                    "Twitch extensions not set for user " + user.getId() + ": bot disabled or account not active");
        }
        return unwrapped(() -> call(user, SimulatedCalls.TWITCH_HELIX_SET_EXTENSIONS, "PUT /users/extensions",
                () -> real().setActiveExtensions(user, placement), () -> {
                    rejectUninstalled(placement, registry.data(user.getId()).installed());
                    AtomicReference<TwitchExtensionPlacement> after = new AtomicReference<>();
                    registry.update(user.getId(), data -> {
                        after.set(merge(data.active(), placement, data.installed()));
                        return data.withActive(after.get());
                    });
                    return after.get();
                }));
    }

    /**
     * One PATCH /channels, with the real client's 401 handling: the token refreshed, the PATCH retried once, the bot
     * paused when either fails and what failed thrown.
     */
    private ChannelUpdateOutcome patch(UserAccount user, CallKey key, String request,
                                       Supplier<ChannelUpdateOutcome> realCall,
                                       Supplier<ChannelUpdateOutcome> simulated) {
        try {
            return call(user, key, request, realCall, simulated);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                throw e;
            }
            OAuthToken token = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                    .orElse(null);
            if (tokens.refreshAccessToken(token, user) == null) {
                log.warn("Twitch token invalid for user {} — pausing bot", user.getId());
                botToggleService.pauseAfterATokenFailure(user);
                throw e;
            }
            try {
                return call(user, key, request + " (retried after a token refresh)", realCall, simulated);
            } catch (RestClientException retryEx) {
                log.warn("Twitch channel update failed for user {} after token refresh — pausing bot: {}",
                        user.getId(), retryEx.getMessage());
                botToggleService.pauseAfterATokenFailure(user);
                throw retryEx;
            }
        }
    }

    /** A read that answers {@code fallback} on a simulated failure, as the real one does on any. */
    private <T> T read(UserAccount user, CallKey key, String request, T fallback, Supplier<T> realCall,
                       Supplier<T> simulated) {
        return unwrapped(() -> {
            try {
                return call(user, key, request, realCall, simulated);
            } catch (RestClientException e) {
                log.warn("Twitch {} failed for user {}: {}", key.call(), user.getId(), e.getMessage());
                return fallback;
            }
        });
    }

    /**
     * Runs {@code key} for the channel. A simulated failure comes out as the {@link RestClientException} the real
     * client would throw; a failure of the real method comes out wrapped in {@link RealCallFailure}, so that no
     * simulated contract handles it twice, and {@link #unwrapped} throws it as it was.
     */
    private <T> T call(UserAccount user, CallKey key, String request, Supplier<T> realCall, Supplier<T> simulated) {
        return runner.run(user.getId(), key, request, () -> {
            try {
                return realCall.get();
            } catch (RuntimeException e) {
                throw new RealCallFailure(e);
            }
        }, simulated, SimulatedCallRunner::httpError);
    }

    private static <T> T unwrapped(Supplier<T> body) {
        try {
            return body.get();
        } catch (RealCallFailure e) {
            throw e.getCause();
        }
    }

    private ChannelUpdateOutcome applied(UserAccount user, String gameId, String gameName,
                                         @Nullable Set<String> ccls) {
        registry.update(user.getId(), data -> {
            var changed = data.withCategory(gameId, gameName);
            return ccls == null ? changed : changed.withCcls(ccls);
        });
        // The same self-change marker as the real client: the channel.update it triggers is Catapult's own
        categoryChangeStateService.recordCatapultChangeAndReturnPrevious(user, gameId);
        return ChannelUpdateOutcome.APPLIED;
    }

    /** The labels the real client would enable for the binding, {@code null} when it sends none. */
    private @Nullable Set<String> cclsSent(UserAccount user, GameBinding binding) {
        UserSettings settings = userSettingsRepository.findById(user.getId()).orElse(null);
        boolean globalCclEnabled = settings == null || settings.isCclFeatureEnabled();
        if (!globalCclEnabled || !binding.isCclEnabled()) {
            return null;
        }
        // the blocklist only applies to persisted bindings, as in the real client
        Set<String> blocked = settings != null && binding.getId() != null ? settings.getBlockedCcls() : Set.of();
        Set<String> enabled = new HashSet<>(editable(binding.getCcls()));
        enabled.removeAll(blocked);
        return enabled;
    }

    private static Set<String> editable(Set<String> ccls) {
        Set<String> kept = new HashSet<>(ccls);
        kept.retainAll(EDITABLE_CCL_IDS);
        return kept;
    }

    /** Twitch answers 400 to a PUT activating an extension the broadcaster has not installed. */
    private static void rejectUninstalled(TwitchExtensionPlacement placement, List<TwitchExtension> installed) {
        Stream.of(placement.component(), placement.overlay(), placement.panel())
                .filter(Objects::nonNull)
                .flatMap(kind -> kind.slots().stream())
                .filter(slot -> slot != null && slot.active())
                .filter(slot -> installed.stream().noneMatch(extension -> extension.id().equals(slot.id())))
                .findFirst()
                .ifPresent(slot -> {
                    throw SimulatedCallRunner.httpError(HttpStatus.BAD_REQUEST.value());
                });
    }

    /**
     * {@code current} with the slots {@code update} carries replaced, as Twitch applies a PUT; an active slot gets
     * its installed extension's name, as Twitch answers it.
     */
    static TwitchExtensionPlacement merge(TwitchExtensionPlacement current, TwitchExtensionPlacement update,
                                          List<TwitchExtension> installed) {
        ComponentPlacement component = current.component() != null ? current.component() : ComponentPlacement.empty();
        OverlayPlacement overlay = current.overlay() != null ? current.overlay() : OverlayPlacement.empty();
        PanelPlacement panel = current.panel() != null ? current.panel() : PanelPlacement.empty();
        if (update.component() != null) {
            component = new ComponentPlacement(
                    slot(component.first(), update.component().first(), installed),
                    slot(component.second(), update.component().second(), installed));
        }
        if (update.overlay() != null) {
            overlay = new OverlayPlacement(slot(overlay.first(), update.overlay().first(), installed));
        }
        if (update.panel() != null) {
            panel = new PanelPlacement(
                    slot(panel.first(), update.panel().first(), installed),
                    slot(panel.second(), update.panel().second(), installed),
                    slot(panel.third(), update.panel().third(), installed));
        }
        return new TwitchExtensionPlacement(component, overlay, panel);
    }

    private static Slot slot(Slot current, @Nullable Slot update, List<TwitchExtension> installed) {
        if (update == null) {
            return current;
        }
        if (!update.active() || update.name() != null) {
            return update;
        }
        String name = installed.stream()
                .filter(extension -> extension.id().equals(update.id()))
                .map(TwitchExtension::name)
                .findFirst()
                .orElse(null);
        return new Slot(true, update.id(), name, update.version(), update.x(), update.y());
    }

    /** Re-read from the database, as the real client does: the caller's copy may predate a toggle. */
    private boolean canActOnTwitch(UserAccount user) {
        return userAccountRepository.findById(user.getId())
                .map(account -> account.isBotEnabled() && account.getStatus() == UserAccount.Status.ACTIVE)
                .orElse(false);
    }

    private TwitchServiceImpl real() {
        TwitchServiceImpl impl = real.getIfAvailable();
        if (impl == null) {
            throw new IllegalStateException("The real Twitch client is not available");
        }
        return impl;
    }

    /** A failure of the real client, carried past the simulated contracts unchanged. */
    private static final class RealCallFailure extends RuntimeException {
        RealCallFailure(RuntimeException cause) {
            super(cause.getMessage(), cause, false, false);
        }

        @Override
        public synchronized RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }
}
