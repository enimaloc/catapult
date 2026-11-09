package fr.enimaloc.catapult.simulation.twitch;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.InjectedEvents;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Twitch's echo of a category change Catapult applied: the {@code channel.update} notification Twitch sends after a
 * PATCH /channels. While the account's {@code twitch.eventsub.channel-update} is {@code REAL} the real echo arrives
 * and nothing is injected; otherwise the simulation injects it, shortly after the call returns as Twitch does, so the
 * real handling recognises Catapult's own change and the channel's stored state follows. The echo follows that
 * setting ({@link InjectedEvents}): its latency comes on top of {@link #DELAY}, an {@code ERROR} or {@code TIMEOUT}
 * drops it. Takes the EventSub service lazily: it is no dependency of the Twitch layer.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class ChannelUpdateEcho {

    /** How long after the PATCH the echo arrives. */
    static final Duration DELAY = Duration.ofMillis(150);

    private final SimulationRegistry registry;
    private final SimulationJournal journal;
    private final ObjectProvider<TwitchEventSubService> eventSub;

    public ChannelUpdateEcho(SimulationRegistry registry, SimulationJournal journal,
                             ObjectProvider<TwitchEventSubService> eventSub) {
        this.registry = registry;
        this.journal = journal;
        this.eventSub = eventSub;
    }

    /**
     * Before a change: makes sure the channel has a previous state to compare the echo with, taken from the
     * simulated data before the change lands in it.
     */
    public void prepare(UserAccount user) {
        TwitchEventSubService service = echoing(user);
        if (service != null) {
            service.seedChannelStateIfAbsent(user);
        }
    }

    /**
     * After a change Twitch accepted: injects its {@code channel.update}, on a virtual thread after {@link #DELAY}.
     * The simulated data takes the new category (and labels, when sent) at once.
     *
     * @param ccls the labels the change set, {@code null} when it left them as they were
     */
    public void echo(UserAccount user, String categoryId, String categoryName, @Nullable Set<String> ccls) {
        TwitchEventSubService service = echoing(user);
        if (service == null) {
            return;
        }
        SimulatedData data = registry.data(user.getId());
        Set<String> labels = ccls == null ? data.ccls() : ccls;
        registry.update(user.getId(), d -> d.withCategory(categoryId, categoryName).withCcls(labels));
        ObjectNode event = JsonNodeFactory.instance.objectNode()
                .put("broadcaster_user_id", user.getTwitchId())
                .put("broadcaster_user_login", InjectedEvents.login(user))
                .put("broadcaster_user_name", InjectedEvents.login(user))
                .put("title", "Simulated stream")
                .put("language", "en")
                .put("category_id", categoryId)
                .put("category_name", categoryName);
        ArrayNode labelArray = event.putArray("content_classification_labels");
        labels.forEach(labelArray::add);
        Thread.ofVirtual().name("simulated-channel-update-echo").start(() -> {
            try {
                Thread.sleep(DELAY);
                if (InjectedEvents.admit(registry, journal, user, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE,
                        "echo category " + categoryId)) {
                    service.injectNotification(user, "channel.update", event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Simulated channel.update echo failed for user {}: {}", user.getId(), e.getMessage(), e);
            }
        });
    }

    /** The EventSub service to inject into, {@code null} when the real echo arrives or there is none. */
    private @Nullable TwitchEventSubService echoing(UserAccount user) {
        if (registry.setting(user.getId(), SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_UPDATE).mode()
                == CallSetting.Mode.REAL) {
            return null;
        }
        return eventSub.getIfAvailable();
    }
}
