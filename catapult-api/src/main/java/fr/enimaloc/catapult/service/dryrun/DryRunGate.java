package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService.CurrentCategory;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the dry-run decorators ({@link DryRunTwitchService}, {@link DryRunTwitchChatService}) ask before and after
 * holding back a write to Twitch: whether this member's write is held, and the trace it leaves instead, in the
 * member's activity log and, when the member is in debug, their debug journal. Nothing here ever throws to the
 * caller: a failing trace is logged at warn, the write staying held.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DryRunGate {

    static final String LEVEL = "INFO";
    static final String SERVICE = "twitch";
    static final String OPERATION_PREFIX = "dry-run.";
    static final URI JOURNAL_URI = URI.create("twitch://dry-run");
    /** The labels a broadcaster can set, as {@code TwitchServiceImpl} sends them. */
    private static final Set<String> EDITABLE_CCL_IDS =
            Set.of("ViolentGraphic", "SexualThemes", "DrugsIntoxication", "Gambling", "ProfanityVulgarity");

    private final DryRunRegistry registry;
    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final SiteRole siteRole;
    private final ActivityLogService activityLog;
    private final DebugMemberRegistry debugMembers;
    private final DebugExchangeRecorder recorder;
    private final StreamTitleService streamTitleService;
    private final ObjectProvider<TwitchEventSubService> eventSub;
    private final ObjectProvider<TwitchatEventDispatcher> twitchat;

    /** The default ("no game") category a reset would send. */
    public record Category(String id, String name) {}

    /**
     * Whether a chat write (message, moderation, shoutout) for {@code user}'s channel is held: the member is in dry
     * run and this site is the active one (a standby refuses as before, nothing being logged).
     */
    public boolean holdsChat(UserAccount user) {
        return user != null && registry.isActive(user.getId()) && siteRole.isActive();
    }

    /**
     * Whether a channel write (category, title, labels, extensions) for {@code user} is held: as {@link #holdsChat},
     * and only when the bot could act on the channel (enabled, account active, re-read as the real client does). A
     * disabled bot or inactive account keeps its own answer (skipped, or refused), nothing being logged.
     */
    public boolean holdsChannel(UserAccount user) {
        if (!holdsChat(user)) {
            return false;
        }
        try {
            return userAccountRepository.findById(user.getId())
                    .map(account -> account.isBotEnabled() && account.getStatus() == UserAccount.Status.ACTIVE)
                    .orElse(false);
        } catch (RuntimeException e) {
            // Unknown state: held, so that nothing public leaves for a member in dry run
            log.warn("Could not read the bot state of {} in dry run, holding the write: {}", user.getId(),
                    e.toString());
            return true;
        }
    }

    /**
     * Notes a held write in the member's activity log and, when the member is in debug, in their debug journal as
     * the {@code dry-run.<operation>} exchange.
     */
    public void note(UserAccount user, String operation, String message) {
        log.info("Dry run of {}: {}", user.getId(), message);
        try {
            activityLog.addEntry(user.getId(), LEVEL, message);
        } catch (RuntimeException e) {
            log.warn("Could not log the dry run of {} in its activity log: {}", user.getId(), e.toString());
        }
        try {
            if (debugMembers.modeOf(user.getId()).isPresent()) {
                RawExchange raw = new RawExchange(Instant.now(), "DRY_RUN", JOURNAL_URI, null,
                        message.getBytes(StandardCharsets.UTF_8), null, null, null, null, 0);
                recorder.record(user.getId(), Direction.OUT, raw, OPERATION_PREFIX + operation, false, SERVICE);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record the dry run of {} in its debug journal: {}", user.getId(), e.toString());
        }
    }

    /**
     * The title the real client would send with {@code binding}, empty when none would be (or it cannot be told).
     * Read only: the channel's current title is not read from Twitch, so a template using it gives no title.
     */
    public Optional<String> plannedTitle(UserAccount user, GameBinding binding) {
        try {
            UserSettings settings = userSettingsRepository.findById(user.getId()).orElse(null);
            if (settings == null) {
                return Optional.empty();
            }
            return streamTitleService.titleFor(user, settings, binding, labelSent(settings, binding), Optional::empty)
                    .map(StreamTitleService.PlannedTitle::title);
        } catch (RuntimeException e) {
            log.warn("Could not tell the title of the dry run of {}: {}", user.getId(), e.toString());
            return Optional.empty();
        }
    }

    /** The default category a reset would send, empty when none is set (the reset is then skipped). */
    public Optional<Category> noGameCategory(UserAccount user) {
        try {
            return userSettingsRepository.findById(user.getId())
                    .filter(settings -> settings.getNoGameTwitchGameId() != null
                            && !settings.getNoGameTwitchGameId().isBlank())
                    .map(settings -> new Category(settings.getNoGameTwitchGameId(),
                            settings.getNoGameTwitchGameName()));
        } catch (RuntimeException e) {
            log.warn("Could not read the default category of {} in dry run: {}", user.getId(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Tells the member's Twitchat about a held category change, as Twitch's {@code channel.update} echo would have
     * for a real one ("changed by Catapult"); nothing when the channel is already on that category.
     */
    public void announceCategory(UserAccount user, String gameId, String gameName) {
        try {
            TwitchEventSubService channels = eventSub.getIfAvailable();
            Optional<CurrentCategory> current = channels == null ? Optional.empty()
                    : channels.currentCategory(user.getId());
            if (current.map(category -> Objects.equals(category.id(), gameId)).orElse(false)) {
                return;
            }
            TwitchatEventDispatcher dispatcher = twitchat.getIfAvailable();
            if (dispatcher == null) {
                return;
            }
            dispatcher.onCategoryChangedByCatapult(user, gameId, gameName,
                    current.map(CurrentCategory::id).filter(id -> !id.isBlank()).orElse(null),
                    current.map(CurrentCategory::name).orElse(null));
        } catch (RuntimeException e) {
            log.warn("Could not announce the dry run of {} on Twitchat: {}", user.getId(), e.toString());
        }
    }

    /** Whether the real client would send at least one content label switched on with {@code binding}. */
    private static boolean labelSent(UserSettings settings, GameBinding binding) {
        if (!settings.isCclFeatureEnabled() || !binding.isCclEnabled()) {
            return false;
        }
        // the blocklist only applies to persisted bindings, as in the real client
        Set<String> enabled = new HashSet<>(binding.getCcls());
        enabled.retainAll(EDITABLE_CCL_IDS);
        if (binding.getId() != null) {
            enabled.removeAll(settings.getBlockedCcls());
        }
        return !enabled.isEmpty();
    }
}
