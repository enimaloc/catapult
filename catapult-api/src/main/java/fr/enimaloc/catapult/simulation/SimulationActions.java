package fr.enimaloc.catapult.simulation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.emergency.MentionSender;
import fr.enimaloc.catapult.service.emergency.SystemMention;
import fr.enimaloc.catapult.service.emergency.SystemMentionEvent;
import fr.enimaloc.catapult.service.stream.SessionTimelineService;
import fr.enimaloc.catapult.service.stream.StreamSessionService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the control panel makes happen on a channel, "as for real": every action enters through the door the real
 * event uses (an EventSub notification handed to {@link TwitchEventSubService}, a presence the simulated getters
 * report then a detection cycle) and never writes its outcome by hand. Each injection follows its event key's setting
 * for the account ({@link InjectedEvents}): refused while it is {@code REAL}, delayed by its latency, dropped on an
 * {@code ERROR} or {@code TIMEOUT}; the platform's own state (live, category) changes all the same.
 */
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulationActions {

    private final SimulationRegistry registry;
    private final SimulationJournal journal;
    private final ObjectProvider<TwitchEventSubService> eventSub;
    private final SchedulerService scheduler;
    private final GetterConfigRepository getterConfigs;
    private final BotToggleService botToggle;
    private final TwitchService twitch;
    private final ApplicationEventPublisher publisher;
    private final SystemTwitchAccountService systemAccount;
    private final UserAccountRepository accounts;
    private final StreamSessionService sessions;
    private final SessionTimelineService timeline;
    /** {@code app.owner-id}: the Twitch id a chat message written "as the instance owner" carries. */
    private final String ownerId;

    public SimulationActions(SimulationRegistry registry, SimulationJournal journal,
                             ObjectProvider<TwitchEventSubService> eventSub, SchedulerService scheduler,
                             GetterConfigRepository getterConfigs, BotToggleService botToggle, TwitchService twitch,
                             ApplicationEventPublisher publisher, SystemTwitchAccountService systemAccount,
                             UserAccountRepository accounts, StreamSessionService sessions,
                             SessionTimelineService timeline, @Value("${app.owner-id:}") String ownerId) {
        this.registry = registry;
        this.journal = journal;
        this.eventSub = eventSub;
        this.scheduler = scheduler;
        this.getterConfigs = getterConfigs;
        this.botToggle = botToggle;
        this.twitch = twitch;
        this.publisher = publisher;
        this.systemAccount = systemAccount;
        this.accounts = accounts;
        this.sessions = sessions;
        this.timeline = timeline;
        this.ownerId = ownerId;
    }

    /**
     * The stream goes live or offline: a {@code stream.online} or {@code stream.offline} notification. Each live
     * carries a stream id of its own ({@code sim-<uuid>}) and its start, so the real handling records its session
     * and the timeline follows it.
     *
     * @throws IllegalStateException when that event is {@code REAL} for the account
     */
    public void setLive(UserAccount user, boolean live) {
        InjectedEvents.requireSimulated(registry, user, live
                ? SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE : SimulatedCalls.TWITCH_EVENTSUB_STREAM_OFFLINE);
        registry.update(user.getId(), data -> data.withLive(live));
        if (live) {
            ObjectNode event = broadcasterEvent(user)
                    .put("id", "sim-" + UUID.randomUUID())
                    .put("type", "live")
                    .put("started_at", Instant.now().toString());
            inject(user, "stream.online", SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE, event, "live");
        } else {
            inject(user, "stream.offline", SimulatedCalls.TWITCH_EVENTSUB_STREAM_OFFLINE, broadcasterEvent(user),
                    "offline");
        }
    }

    /**
     * The account starts playing {@code name} on {@code provider}: the other platforms stop reporting a game, the
     * account gets a getter for the platform when it had none (an existing one, disabled, is enabled), and a
     * detection cycle runs through the real chain.
     *
     * @param sourceId what the platform identifies the game by (Steam app id, Xbox title id…)
     * @throws IllegalStateException on Steam for an account without a Steam id: the Steam presence is looked up by it
     */
    public void play(UserAccount user, GetterConfig.Provider provider, String sourceId, String name) {
        if (provider == GetterConfig.Provider.STEAM && (user.getSteamId() == null || user.getSteamId().isBlank())) {
            throw new IllegalStateException("This account has no Steam id: link a Steam account to play on Steam");
        }
        registry.update(user.getId(), data -> data.withPresence(Map.of(provider, new SimulatedPresence(sourceId, name))));
        ensureGetter(user, provider);
        scheduler.triggerManualCheck(user);
    }

    /** No platform reports a game any more, then a detection cycle runs. */
    public void stopPlaying(UserAccount user) {
        registry.update(user.getId(), data -> data.withPresence(Map.of()));
        scheduler.triggerManualCheck(user);
    }

    /**
     * The streamer changes the category on Twitch themselves: a {@code channel.update} notification, which the real
     * handling tells apart from Catapult's own changes. The labels stay as the simulated data has them.
     *
     * @throws IllegalStateException when {@code channel.update} is {@code REAL} for the account
     */
    public void manualCategoryChange(UserAccount user, String categoryId, String categoryName) {
        TwitchEventSubService service = eventSub();
        InjectedEvents.requireSimulated(registry, user, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE);
        SimulatedData data = registry.data(user.getId());
        ObjectNode event = broadcasterEvent(user);
        event.put("title", "Simulated stream")
                .put("language", "en")
                .put("category_id", categoryId)
                .put("category_name", categoryName);
        ArrayNode labels = event.putArray("content_classification_labels");
        data.ccls().forEach(labels::add);
        // Injected first: a channel without a previous state yet is seeded from the category it had
        if (InjectedEvents.admit(registry, journal, user, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE,
                "category " + categoryId + " " + categoryName)) {
            service.injectNotification(user, "channel.update", event);
        }
        // The channel the simulated Helix answers with follows the change
        registry.update(user.getId(), d -> d.withCategory(categoryId, categoryName));
    }

    /**
     * The streamer raids {@code toLogin}: an outgoing {@code channel.raid} notification. The raided channel's id is
     * the Twitch id of the Catapult account with that login (whatever its case), empty when none has it, as Twitch
     * would give it.
     *
     * @throws IllegalStateException when {@code channel.raid} is {@code REAL} for the account
     */
    public void raidOut(UserAccount user, String toLogin) {
        String toId = accounts.findFirstByTwitchUsernameIgnoreCase(toLogin)
                .map(UserAccount::getTwitchId)
                .orElse("");
        ObjectNode event = JsonNodeFactory.instance.objectNode()
                .put("from_broadcaster_user_id", user.getTwitchId())
                .put("from_broadcaster_user_login", InjectedEvents.login(user))
                .put("from_broadcaster_user_name", InjectedEvents.login(user))
                .put("to_broadcaster_user_id", toId)
                .put("to_broadcaster_user_login", toLogin)
                .put("to_broadcaster_user_name", toLogin)
                .put("viewers", 0);
        inject(user, "channel.raid", SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_RAID, event, "raid to " + toLogin);
    }

    /**
     * Adds a closed period of {@code duration} before the current session's oldest one, its start moved back as much
     * ({@link SessionTimelineService#addPast}): a long session is built without waiting.
     *
     * @param gameName the detected game, {@code null} for none
     * @throws IllegalStateException when the account has no session in progress
     */
    public void addPastSegment(UserAccount user, String categoryId, String categoryName, String gameName,
                               Duration duration) {
        if (sessions.find(user).filter(StreamSession::isOngoing).isEmpty()) {
            throw new IllegalStateException("This account is not live: set it live before adding a past period");
        }
        timeline.addPast(user, categoryId, categoryName, gameName, duration);
    }

    /** The periods of the account's current (or last) session, oldest first. */
    public List<StreamCategorySegment> sessionTimeline(UserAccount user) {
        return timeline.currentSession(user);
    }

    /**
     * Someone writes {@code text} in the channel's chat. Only a message that starts by mentioning the system account
     * does anything, read by the same {@link SystemMention#parse} as the real chat; any other text is only journaled
     * (the {@code !} commands are retired).
     *
     * @param owner whether the author is the instance owner: their Twitch id is then {@code app.owner-id}, else an
     *              id derived from the login, so that distinct logins are distinct authors
     * @throws IllegalStateException when {@code channel.chat.message} is {@code REAL} for the account, when the text
     *                               mentions someone while no system account is linked, or for an owner author
     *                               while {@code app.owner-id} is not set
     */
    public void chatMessage(UserAccount channel, String senderLogin, SenderRole role, boolean owner, String text) {
        String systemLogin = systemAccount.getSystemTwitchUsername();
        Optional<String> mentioned = mentionedLogin(text);
        if (mentioned.isPresent() && (systemLogin == null || systemLogin.isBlank())) {
            throw new IllegalStateException("No system account is linked: mention commands cannot be simulated");
        }
        if (owner && ownerId.isBlank()) {
            throw new IllegalStateException("app.owner-id is not set: no message can be sent as the owner");
        }
        InjectedEvents.requireSimulated(registry, channel, SimulatedCalls.TWITCH_EVENTSUB_CHAT_MESSAGE);
        if (!InjectedEvents.admit(registry, journal, channel, SimulatedCalls.TWITCH_EVENTSUB_CHAT_MESSAGE,
                senderLogin + " (" + role + (owner ? ", owner" : "") + "): " + text)) {
            return;
        }
        if (mentioned.isEmpty()) {
            return;
        }
        String login = mentioned.get();
        String userId = login.equalsIgnoreCase(systemLogin) ? systemAccount.getSystemTwitchId() : "sim:" + login;
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("text", text);
        ArrayNode fragments = message.putArray("fragments");
        ObjectNode mention = fragments.addObject().put("type", "mention").put("text", "@" + login);
        mention.putObject("mention").put("user_login", login.toLowerCase(Locale.ROOT)).put("user_id", userId);
        String rest = text.substring(login.length() + 1);
        if (!rest.isEmpty()) {
            fragments.addObject().put("type", "text").put("text", rest);
        }
        SystemMention.parse(message, systemAccount::getSystemTwitchId).ifPresent(parsed -> publisher.publishEvent(
                new SystemMentionEvent(channel,
                        new MentionSender(owner ? ownerId : "sim:" + senderLogin, senderLogin, role), parsed)));
    }

    /** The login a text starts by mentioning ({@code @login}, then a space or the end), if it does. */
    private static Optional<String> mentionedLogin(String text) {
        if (!text.startsWith("@")) {
            return Optional.empty();
        }
        int end = text.indexOf(' ');
        String login = text.substring(1, end < 0 ? text.length() : end);
        return login.isEmpty() ? Optional.empty() : Optional.of(login);
    }

    /** Turns the bot on or off as the streamer's toggle does. */
    public void setBot(UserAccount user, boolean enabled) {
        botToggle.setBotEnabled(user, enabled);
    }

    /** The games the panel offers to play: the Twitch category search, in its own mode. */
    public List<TwitchCategory> searchGames(UserAccount user, String query) {
        return twitch.searchCategories(user, query);
    }

    private void inject(UserAccount user, String type, CallKey key, ObjectNode event, String request) {
        TwitchEventSubService service = eventSub();
        if (InjectedEvents.admit(registry, journal, user, key, request)) {
            service.injectNotification(user, type, event);
        }
    }

    private TwitchEventSubService eventSub() {
        TwitchEventSubService service = eventSub.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("The real EventSub service is not available");
        }
        return service;
    }

    /** The account's getter for {@code provider}, enabled; created last in priority when absent. */
    private void ensureGetter(UserAccount user, GetterConfig.Provider provider) {
        getterConfigs.findByUserAndProvider(user, provider).ifPresentOrElse(config -> {
            if (!config.isEnabled()) {
                config.setEnabled(true);
                getterConfigs.save(config);
            }
        }, () -> {
            GetterConfig config = new GetterConfig();
            config.setUser(user);
            config.setProvider(provider);
            config.setPriority(getterConfigs.findByUserOrderByPriorityAsc(user).stream()
                    .mapToInt(GetterConfig::getPriority).max().orElse(-1) + 1);
            config.setEnabled(true);
            getterConfigs.save(config);
        });
    }

    private static ObjectNode broadcasterEvent(UserAccount user) {
        return JsonNodeFactory.instance.objectNode()
                .put("broadcaster_user_id", user.getTwitchId())
                .put("broadcaster_user_login", InjectedEvents.login(user))
                .put("broadcaster_user_name", InjectedEvents.login(user));
    }
}
