package fr.enimaloc.catapult.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetricsEventListener {

    private final MeterRegistry registry;
    private final Map<UUID, Instant> streamStartTimes = new ConcurrentHashMap<>();

    // Meters à tags fixes, pré-enregistrés pour être exportés à 0 dès le démarrage
    private final Counter noGameDetected;
    private final Counter streamOnline;
    private final Counter streamOffline;
    private final Counter accountCreated;
    private final Counter twitchLogin;
    private final Timer streamDuration;

    public MetricsEventListener(MeterRegistry registry) {
        this.registry = registry;
        this.noGameDetected = Counter.builder("catapult.game.no_detected").register(registry);
        this.streamOnline = Counter.builder("catapult.stream.online").register(registry);
        this.streamOffline = Counter.builder("catapult.stream.offline").register(registry);
        this.accountCreated = Counter.builder("catapult.account.created").register(registry);
        this.twitchLogin = Counter.builder("catapult.auth.login")
                .tag("provider", "twitch")
                .register(registry);
        this.streamDuration = Timer.builder("catapult.stream.duration")
                .description("Durée d'un stream, de online à offline")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMinutes(10))
                .maximumExpectedValue(Duration.ofHours(12))
                .register(registry);
    }

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        Counter.builder("catapult.game.detected")
                .tag("source", event.getDetectedGame().getSourceType().name())
                .register(registry)
                .increment();
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        noGameDetected.increment();
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        streamStartTimes.put(event.getUser().getId(), Instant.now());
        streamOnline.increment();
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        Instant start = streamStartTimes.remove(event.getUser().getId());
        if (start != null) {
            streamDuration.record(Duration.between(start, Instant.now()));
        }
        streamOffline.increment();
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        accountCreated.increment();
    }

    @EventListener
    public void onExperimentActivated(ExperimentActivatedEvent event) {
        Counter.builder("catapult.experiment.activated")
                .tag("experiment", event.getExperimentKey())
                .register(registry)
                .increment();
    }

    @EventListener
    public void onTwitchLogin(TwitchLoginEvent event) {
        twitchLogin.increment();
    }

    @EventListener
    public void onChannelCategoryChanged(ChannelCategoryChangedEvent event) {
        Counter.builder("catapult.channel.category_changed")
                .tag("category_id", event.getCategoryId())
                .register(registry)
                .increment();
    }

    @EventListener
    public void onChannelCclChanged(ChannelCclChangedEvent event) {
        List<String> cclIds = event.getCclIds();
        if (cclIds.isEmpty()) {
            Counter.builder("catapult.channel.ccl_changed")
                    .tag("ccl_id", "none")
                    .register(registry)
                    .increment();
        } else {
            for (String cclId : cclIds) {
                Counter.builder("catapult.channel.ccl_changed")
                        .tag("ccl_id", cclId)
                        .register(registry)
                        .increment();
            }
        }
    }
}
