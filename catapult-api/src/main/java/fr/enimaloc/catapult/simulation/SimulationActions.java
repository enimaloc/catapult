package fr.enimaloc.catapult.simulation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    public SimulationActions(SimulationRegistry registry, SimulationJournal journal,
                             ObjectProvider<TwitchEventSubService> eventSub, SchedulerService scheduler,
                             GetterConfigRepository getterConfigs, BotToggleService botToggle, TwitchService twitch) {
        this.registry = registry;
        this.journal = journal;
        this.eventSub = eventSub;
        this.scheduler = scheduler;
        this.getterConfigs = getterConfigs;
        this.botToggle = botToggle;
        this.twitch = twitch;
    }

    /**
     * The stream goes live or offline: a {@code stream.online} or {@code stream.offline} notification.
     *
     * @throws IllegalStateException when that event is {@code REAL} for the account
     */
    public void setLive(UserAccount user, boolean live) {
        InjectedEvents.requireSimulated(registry, user, live
                ? SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE : SimulatedCalls.TWITCH_EVENTSUB_STREAM_OFFLINE);
        registry.update(user.getId(), data -> data.withLive(live));
        if (live) {
            ObjectNode event = broadcasterEvent(user).put("type", "live").put("started_at", Instant.now().toString());
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
     * The streamer raids {@code toLogin}: an outgoing {@code channel.raid} notification.
     *
     * @throws IllegalStateException when {@code channel.raid} is {@code REAL} for the account
     */
    public void raidOut(UserAccount user, String toLogin) {
        ObjectNode event = JsonNodeFactory.instance.objectNode()
                .put("from_broadcaster_user_id", user.getTwitchId())
                .put("from_broadcaster_user_login", InjectedEvents.login(user))
                .put("from_broadcaster_user_name", InjectedEvents.login(user))
                .put("to_broadcaster_user_id", "")
                .put("to_broadcaster_user_login", toLogin)
                .put("to_broadcaster_user_name", toLogin)
                .put("viewers", 0);
        inject(user, "channel.raid", SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_RAID, event, "raid to " + toLogin);
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
