package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.ChannelConnectionChangedEvent;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import fr.enimaloc.catapult.event.DtddMappingChangedEvent;
import fr.enimaloc.catapult.event.binding.BindingDeletedEvent;
import fr.enimaloc.catapult.event.binding.BindingIgnoredStateEvent;
import fr.enimaloc.catapult.event.binding.BindingUpdatedEvent;
import fr.enimaloc.catapult.event.binding.CclStateEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSavedEvent;
import fr.enimaloc.catapult.event.tw.TwEnabledStateEvent;
import fr.enimaloc.catapult.event.tw.TwResetEvent;
import fr.enimaloc.catapult.event.tw.TwUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Relays catapult-api's channel events ({@link ChannelEvents}, on catapult:events:channel:{ownerId},
 * envelope {name, data, ts}) as the {@link ChannelUpdatedEvent}s the dashboards (ChannelEventStreamController)
 * and navbars (NavEventStreamController) of this instance follow, keyed by the payload's {@code channelLogin}.
 * Every catapult-web instance gets every event: whichever one a browser is connected to tells it. Not in
 * mock mode, where MockApiService and MockAdminController publish them themselves.
 */
@Slf4j
@Configuration
@Profile("!mock")
public class ChannelEventRedisBridge {

    static final String PREFIX = ChannelEvents.REDIS_PREFIX;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    RedisMessageListenerContainer channelEventListener(RedisConnectionFactory connectionFactory,
                                                       ApplicationEventPublisher events) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Started by startChannelEventListener, which survives an absent Redis.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> forward(events,
                new String(message.getChannel(), StandardCharsets.UTF_8),
                new String(message.getBody(), StandardCharsets.UTF_8)), new PatternTopic(PREFIX + "*"));
        return container;
    }

    /** A Redis that is down at boot costs the live dashboards and navbars, not the whole web. */
    @Bean
    ApplicationRunner startChannelEventListener(RedisMessageListenerContainer channelEventListener) {
        return args -> {
            try {
                channelEventListener.start();
            } catch (RuntimeException e) {
                log.error("Channel events will not be relayed, Redis is unreachable", e);
            }
        };
    }

    static void forward(ApplicationEventPublisher publisher, String channel, String body) {
        events(channel, body).forEach(publisher::publishEvent);
    }

    /** What the event {@code body} published on {@code channel} tells the pages; none when it is not one for them. */
    static List<ChannelUpdatedEvent> events(String channel, String body) {
        if (!channel.startsWith(PREFIX)) return List.of();
        try {
            UUID.fromString(channel.substring(PREFIX.length()));
            JsonNode envelope = JSON.readTree(body);
            JsonNode data = envelope.get("data");
            String name = envelope.path("name").asString(null);
            if (name == null || data == null || !data.isObject()) throw new IllegalArgumentException("no name or data");
            String login = data.path("channelLogin").asString(null);
            if (login == null || login.isBlank()) throw new IllegalArgumentException("no channelLogin");
            return events(name, login.toLowerCase(Locale.ROOT), data);
        } catch (RuntimeException e) {
            log.warn("Dropped a malformed channel event on {}: {}", channel, e.getMessage());
            return List.of();
        }
    }

    private static List<ChannelUpdatedEvent> events(String name, String login, JsonNode data) {
        return switch (name) {
            case ChannelEvents.BOT_TOGGLED -> List.of(new BotStateChangedEvent(login,
                    read(data, ChannelEvents.BotToggled.class).enabled()));
            case ChannelEvents.STREAM_STATE_CHANGED -> List.of(new ChannelLiveStateEvent(login,
                    read(data, ChannelEvents.StreamStateChanged.class).isLive()));
            case ChannelEvents.GAME_DETECTED -> {
                var game = read(data, ChannelEvents.GameDetected.class);
                yield List.of(new GameChangedEvent(login, game.bindingId(), game.sourceType(), game.sourceName()));
            }
            case ChannelEvents.GAME_CLEARED -> List.of(new GameChangedEvent(login, null, null, null));
            case ChannelEvents.BINDING_UPSERTED -> binding(login, read(data, ChannelEvents.BindingUpserted.class).binding());
            case ChannelEvents.BINDING_DELETED -> List.of(new BindingDeletedEvent(login,
                    read(data, ChannelEvents.BindingDeleted.class).bindingId()));
            case ChannelEvents.SETTINGS_UPDATED -> List.of(new ChannelSettingsUpdatedEvent(login,
                    read(data, ChannelEvents.SettingsUpdated.class).settings()));
            case ChannelEvents.STEAM_PROFILE_CHANGED -> steam(login, read(data, ChannelEvents.SteamProfileChanged.class).profile());
            case ChannelEvents.CONNECTION_CHANGED -> connection(login, read(data, ChannelEvents.ConnectionChanged.class));
            case ChannelEvents.DTDD_MAPPING_CHANGED -> List.of(new DtddMappingChangedEvent(login));
            case ChannelEvents.MINECRAFT_LINK_CHANGED -> {
                var link = read(data, ChannelEvents.MinecraftLinkChanged.class);
                yield "NONE".equals(link.status()) ? List.of(new MinecraftDisconnectedEvent(login))
                        : List.of(new MinecraftSyncEvent(login, link.status(), link.minecraftName()));
            }
            default -> List.of();
        };
    }

    /** A binding's whole state, as each of the dashboard's binding events. */
    private static List<ChannelUpdatedEvent> binding(String login, BindingDto binding) {
        if (binding == null || binding.id() == null) throw new IllegalArgumentException("no binding");
        String id = binding.id();
        Set<String> ccls = binding.ccls() == null ? Set.of() : binding.ccls();
        Set<String> tws = binding.tws() == null ? Set.of() : binding.tws();
        List<ChannelUpdatedEvent> events = new ArrayList<>();
        events.add(new BindingUpdatedEvent(login, id, binding.twitchGameId(), binding.twitchGameName(), ccls));
        events.add(new CclStateEvent(login, id, binding.cclEnabled()));
        events.add(new BindingIgnoredStateEvent(login, id, binding.ignored()));
        events.add(new TwEnabledStateEvent(login, id, binding.twEnabled()));
        // Without an override, the binding follows the channel's defaults: what a reset leaves it with.
        events.add(binding.twOverride() ? new TwUpdatedEvent(login, id, tws) : new TwResetEvent(login, id));
        return events;
    }

    /** A Steam profile's state, as the Steam card's events. */
    private static List<ChannelUpdatedEvent> steam(String login, ChannelEvents.SteamProfile profile) {
        if (profile == null) throw new IllegalArgumentException("no profile");
        return List.of(
                new SteamConnectionStateEvent(login, profile.hasSteam(), profile.rateLimited(), profile.offlineMode(),
                        profile.profilePrivate()),
                profile.hasPersonalToken() ? new SteamTokenSavedEvent(login, profile.tokenShared())
                        : new SteamTokenDeletedEvent(login));
    }

    private static List<ChannelUpdatedEvent> connection(String login, ChannelEvents.ConnectionChanged change) {
        List<ChannelUpdatedEvent> events = new ArrayList<>();
        events.add(new ChannelConnectionChangedEvent(login, change.provider(), change.connected()));
        if ("STEAM".equals(change.provider())) {
            if (change.profile() != null) {
                events.addAll(steam(login, change.profile()));
            } else if (!change.connected()) {
                events.add(new SteamConnectionStateEvent(login, false, false, false, false));
            }
        }
        return events;
    }

    private static <T> T read(JsonNode data, Class<T> type) {
        return JSON.treeToValue(data, type);
    }
}
