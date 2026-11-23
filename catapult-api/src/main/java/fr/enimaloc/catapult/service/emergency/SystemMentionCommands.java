package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.ChannelDashboardActions;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Answers {@code off}, {@code on}, {@code emergency} and {@code refresh} when the system account is mentioned in a channel's chat. Only
 * moderators, the broadcaster and the instance owner are heard; a disabled bot hears nothing but {@code on}. Not the
 * retired chat command system: the replies come from {@code bot.mention.*} messages, in French.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMentionCommands {

    private static final Locale REPLY_LOCALE = Locale.FRENCH;
    private static final String ALL = "all";
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd/MM HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final UserAccountRepository userAccountRepository;
    private final BotToggleService botToggleService;
    private final EmergencyStopService emergencyStopService;
    private final EmergencyContextCollector contextCollector;
    private final GameStateService gameStateService;
    private final ActivityLogService activityLog;
    private final TwitchChatService chat;
    private final MessageSource messages;
    private final EmergencyProperties properties;
    private final ChannelDashboardActions dashboardActions;

    /**
     * Runs {@code refresh}: a detection cycle waits for the one in progress and calls the providers, so it must not
     * hold the chat connection's thread. Replaced by a direct executor in the tests.
     */
    private Executor refreshExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${app.owner-id:}")
    private String ownerId;

    @EventListener
    public void onMention(SystemMentionEvent event) {
        handle(event.channel(), event.sender(), event.mention());
    }

    public void handle(UserAccount channel, MentionSender sender, SystemMention mention) {
        boolean owner = isOwner(sender);
        if (!owner && (sender.role() == null || sender.role().ordinal() < SenderRole.MODERATOR.ordinal())) {
            return;
        }
        // The connection captured its user when it opened: whether the bot is enabled is read again from the database
        UserAccount current = userAccountRepository.findById(channel.getId()).orElse(null);
        if (current == null) {
            return;
        }
        if (!current.isBotEnabled() && !"on".equals(mention.command())) {
            return;
        }
        switch (mention.command()) {
            case "off" -> off(current, sender);
            case "on" -> on(current, sender);
            case "emergency" -> emergency(current, sender, mention.args(), owner);
            case "refresh" -> refreshExecutor.execute(() -> refresh(current, sender));
            default -> log.debug("Ignoring unknown mention command '{}' in {}", mention.command(), current.getId());
        }
    }

    private boolean isOwner(MentionSender sender) {
        return ownerId != null && !ownerId.isBlank() && ownerId.equals(sender.twitchId());
    }

    private void off(UserAccount channel, MentionSender sender) {
        // Confirm first: once disabled, the bot no longer answers
        reply(channel, "bot.mention.off", sender.login());
        botToggleService.setBotEnabled(channel, false);
        activityLog.addEntry(channel.getId(), "INFO", "Bot disabled from the chat by " + sender.login());
    }

    private void on(UserAccount channel, MentionSender sender) {
        botToggleService.setBotEnabled(channel, true);
        emergencyStopService.liftChannel(channel, sender.login());
        reply(channel, "bot.mention.on", sender.login());
        activityLog.addEntry(channel.getId(), "INFO", "Bot enabled from the chat by " + sender.login());
    }

    /**
     * Detects the game again as if it had just been picked up: the game remembered as current is forgotten, so the
     * check that follows applies it (category, title, tags...) even when it did not change.
     */
    private void refresh(UserAccount channel, MentionSender sender) {
        if (channel.getStatus() != UserAccount.Status.ACTIVE) {
            reply(channel, "bot.mention.refresh.inactive");
            return;
        }
        Optional<DetectedGame> game;
        try {
            gameStateService.forgetLastKnownGame(channel.getId());
            dashboardActions.recheckGame(channel);
            game = gameStateService.getLastKnownGame(channel);
        } catch (RuntimeException e) {
            log.error("Refresh asked by {} failed in the chat of {}", sender.login(), channel.getId(), e);
            reply(channel, "bot.mention.refresh.error");
            return;
        }
        activityLog.addEntry(channel.getId(), "INFO", "Game detection refreshed from the chat by " + sender.login());
        game.map(DetectedGame::getSourceName).filter(name -> name != null && !name.isBlank()).ifPresentOrElse(
                name -> reply(channel, "bot.mention.refresh.done", name),
                () -> reply(channel, "bot.mention.refresh.none"));
    }

    private void emergency(UserAccount channel, MentionSender sender, List<String> args, boolean owner) {
        if (owner) {
            ownerEmergency(channel, sender, args);
            return;
        }
        Optional<GetterConfig.Provider> provider = args.isEmpty() ? deduceProvider(channel) : parse(args.getFirst());
        if (provider.isEmpty()) {
            reply(channel, args.isEmpty() ? "bot.mention.provider.required" : "bot.mention.provider.unknown",
                    args.isEmpty() ? null : args.getFirst());
            return;
        }
        if (!emergencyStopService.isProviderUsed(channel, provider.get())) {
            // Nothing will be recorded: no need to gather the context (a Twitch call) for it
            reply(channel, "bot.mention.emergency.notUsed", displayName(provider.get().name()));
            return;
        }
        ReportOutcome outcome;
        try {
            ReportContext context = contextCollector.collect(channel);
            outcome = emergencyStopService.report(channel, provider.get(), sender.twitchId(),
                    sender.login(), context.json(), context.streamId(), context.streamStartedAt());
        } catch (RuntimeException e) {
            log.error("Emergency report of {} by {} failed in the chat of {}", provider.get(), sender.login(),
                    channel.getId(), e);
            reply(channel, "bot.mention.error");
            return;
        }
        String name = displayName(provider.get().name());
        switch (outcome.kind()) {
            case CHANNEL_STOP -> reply(channel, "bot.mention.emergency.channelStop", name, outcome.votes(),
                    outcome.threshold(), properties.getStopDuration().toHours(), systemMention());
            case GLOBAL_STOP -> reply(channel, "bot.mention.emergency.globalStop", name, expiry(outcome.expiresAt()));
            case GLOBAL_EXTENDED -> reply(channel, "bot.mention.emergency.globalExtended", name,
                    expiry(outcome.expiresAt()));
            case GLOBAL_ACTIVE -> reply(channel, "bot.mention.emergency.globalActive", name);
            case PROVIDER_NOT_USED -> reply(channel, "bot.mention.emergency.notUsed", name);
        }
    }

    private void ownerEmergency(UserAccount channel, MentionSender sender, List<String> args) {
        String first = args.isEmpty() ? null : args.getFirst();
        if ("status".equalsIgnoreCase(first)) {
            status(channel);
        } else if ("resume".equalsIgnoreCase(first)) {
            String target = args.size() > 1 ? args.get(1) : null;
            if (target == null) {
                reply(channel, "bot.mention.provider.required");
                return;
            }
            if (!isProviderOrAll(target)) {
                reply(channel, "bot.mention.provider.unknown", target);
                return;
            }
            int lifted = emergencyStopService.ownerResume(target, sender.login());
            reply(channel, "bot.mention.owner.resumed", displayName(target), lifted);
        } else {
            String target = first;
            if (target == null) {
                target = deduceProvider(channel).map(GetterConfig.Provider::name).orElse(null);
                if (target == null) {
                    reply(channel, "bot.mention.provider.required");
                    return;
                }
            } else if (!isProviderOrAll(target)) {
                reply(channel, "bot.mention.provider.unknown", target);
                return;
            }
            emergencyStopService.ownerStop(target, sender.login());
            reply(channel, "bot.mention.owner.stopped", displayName(target));
        }
    }

    private void status(UserAccount channel) {
        List<ProviderEmergencyStop> active = emergencyStopService.activeStops();
        if (active.isEmpty()) {
            reply(channel, "bot.mention.status.none");
            return;
        }
        String list = active.stream()
                .map(s -> displayName(s.getProvider()) + " (" + s.getScope().name().toLowerCase(Locale.ROOT)
                        + (s.getExpiresAt() == null ? "" : ", " + expiry(s.getExpiresAt())) + ")")
                .collect(Collectors.joining(", "));
        reply(channel, "bot.mention.status", list);
    }

    private Optional<GetterConfig.Provider> deduceProvider(UserAccount channel) {
        return gameStateService.getLastKnownGame(channel)
                .flatMap(game -> game.getSourceType() == null ? Optional.empty() : parse(game.getSourceType().name()));
    }

    private static Optional<GetterConfig.Provider> parse(String name) {
        try {
            return Optional.of(GetterConfig.Provider.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isProviderOrAll(String name) {
        return ALL.equalsIgnoreCase(name) || parse(name).isPresent();
    }

    /** The provider as the store labels show it (Xbox, Battle.net...); {@code ALL} and unknown names stay as is. */
    private String displayName(String provider) {
        String upper = provider.toUpperCase(Locale.ROOT);
        String label = messages.getMessage("game_info.store." + upper, null, upper, REPLY_LOCALE);
        return label == null ? upper : label;
    }

    /** {@code @<system login>}, or a generic wording when no system account is known. */
    private String systemMention() {
        return userAccountRepository.findBySystemAccountTrue().map(UserAccount::getTwitchUsername)
                .filter(login -> !login.isBlank()).map(login -> "@" + login)
                .orElseGet(() -> messages.getMessage("bot.mention.botFallback", null, "le bot", REPLY_LOCALE));
    }

    private static String expiry(Instant at) {
        return at == null ? "" : EXPIRY.format(at);
    }

    /** A failed reply must not undo the command that follows it (the disabling in particular). */
    private void reply(UserAccount channel, String key, Object... args) {
        try {
            chat.sendMessage(channel, messages.getMessage(key, args, REPLY_LOCALE));
        } catch (RuntimeException e) {
            log.warn("Could not answer '{}' in the chat of {}: {}", key, channel.getId(), e.getMessage());
        }
    }
}
