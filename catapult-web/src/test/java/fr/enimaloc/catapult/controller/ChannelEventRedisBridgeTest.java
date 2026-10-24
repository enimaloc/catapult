package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.ChannelConnectionChangedEvent;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent;
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
import fr.enimaloc.catapult.TestRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** catapult-api's channel events, as catapult-api writes them, become the pages' own events. */
class ChannelEventRedisBridgeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final String channel = ChannelEvents.REDIS_PREFIX + owner;

    /** The envelope RedisEventPublisher writes around {@code data}. */
    private String envelope(String name, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("name", name);
        envelope.put("data", data);
        envelope.put("ts", 1L);
        return JSON.writeValueAsString(envelope);
    }

    @Test
    void forwardsTheEvents_toThisInstancesListeners_byLowerCaseLogin() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        ChannelEventRedisBridge.forward(publisher, channel, envelope(ChannelEvents.BOT_TOGGLED,
                new ChannelEvents.BotToggled(owner.toString(), "Streamer", true)));

        verify(publisher).publishEvent(new BotStateChangedEvent("streamer", true));
    }

    @Test
    void liveState_gameAndGameCleared() {
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.STREAM_STATE_CHANGED,
                new ChannelEvents.StreamStateChanged(owner.toString(), "streamer", true))))
                .containsExactly(new ChannelLiveStateEvent("streamer", true));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.GAME_DETECTED,
                new ChannelEvents.GameDetected(owner.toString(), "streamer", "b1", "Halo", "XBOX"))))
                .containsExactly(new GameChangedEvent("streamer", "b1", "XBOX", "Halo"));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.GAME_CLEARED,
                new ChannelEvents.GameCleared(owner.toString(), "streamer"))))
                .containsExactly(new GameChangedEvent("streamer", null, null, null));
    }

    @Test
    void aBinding_becomesEachOfItsEvents() {
        BindingDto binding = new BindingDto("b1", "AUTO", "STEAM", "Doom", "7", "DOOM", true, false,
                Set.of("ccl1"), true, true, Set.of("tw1"));

        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.BINDING_UPSERTED,
                new ChannelEvents.BindingUpserted(owner.toString(), "streamer", binding)))).containsExactly(
                new BindingUpdatedEvent("streamer", "b1", "7", "DOOM", Set.of("ccl1")),
                new CclStateEvent("streamer", "b1", false),
                new BindingIgnoredStateEvent("streamer", "b1", true),
                new TwEnabledStateEvent("streamer", "b1", true),
                new TwUpdatedEvent("streamer", "b1", Set.of("tw1")));
    }

    @Test
    void aBindingWithoutTwOverride_isReset() {
        BindingDto binding = new BindingDto("b1", "AUTO", "STEAM", "Doom", "7", "DOOM", false, true,
                Set.of(), false, false, Set.of("auto"));

        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.BINDING_UPSERTED,
                new ChannelEvents.BindingUpserted(owner.toString(), "streamer", binding))))
                .contains(new TwResetEvent("streamer", "b1"), new TwEnabledStateEvent("streamer", "b1", false));
    }

    @Test
    void deletionSettingsAndDtdd() {
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.BINDING_DELETED,
                new ChannelEvents.BindingDeleted(owner.toString(), "streamer", "b1"))))
                .containsExactly(new BindingDeletedEvent("streamer", "b1"));
        ChannelEvents.Settings settings = new ChannelEvents.Settings(new ChannelEvents.Settings.Ccl(true, Set.of()),
                new ChannelEvents.Settings.Tw(false, Set.of()), null, null, null);
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.SETTINGS_UPDATED,
                new ChannelEvents.SettingsUpdated(owner.toString(), "streamer", settings))))
                .containsExactly(new ChannelSettingsUpdatedEvent("streamer", settings));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.DTDD_MAPPING_CHANGED,
                new ChannelEvents.DtddMappingChanged(owner.toString(), "streamer"))))
                .containsExactly(new DtddMappingChangedEvent("streamer"));
    }

    @Test
    void steamProfile_andConnections() {
        ChannelEvents.SteamProfile profile = new ChannelEvents.SteamProfile(true, true, false, true, null, true, true);
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.STEAM_PROFILE_CHANGED,
                new ChannelEvents.SteamProfileChanged(owner.toString(), "streamer", profile)))).containsExactly(
                new SteamConnectionStateEvent("streamer", true, true, false, true),
                new SteamTokenSavedEvent("streamer", true));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.CONNECTION_CHANGED,
                new ChannelEvents.ConnectionChanged(owner.toString(), "streamer", "STEAM", false, null)))).containsExactly(
                new ChannelConnectionChangedEvent("streamer", "STEAM", false),
                new SteamConnectionStateEvent("streamer", false, false, false, false));
        ChannelEvents.SteamProfile noToken = new ChannelEvents.SteamProfile(true, false, false, false, null, false, false);
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.STEAM_PROFILE_CHANGED,
                new ChannelEvents.SteamProfileChanged(owner.toString(), "streamer", noToken))))
                .contains(new SteamTokenDeletedEvent("streamer"));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.CONNECTION_CHANGED,
                new ChannelEvents.ConnectionChanged(owner.toString(), "streamer", "STEAM", true, noToken)))).containsExactly(
                new ChannelConnectionChangedEvent("streamer", "STEAM", true),
                new SteamConnectionStateEvent("streamer", true, false, false, false),
                new SteamTokenDeletedEvent("streamer"));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.CONNECTION_CHANGED,
                new ChannelEvents.ConnectionChanged(owner.toString(), "streamer", "DISCORD", false, null))))
                .containsExactly(new ChannelConnectionChangedEvent("streamer", "DISCORD", false));
    }

    @Test
    void minecraftLink() {
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.MINECRAFT_LINK_CHANGED,
                new ChannelEvents.MinecraftLinkChanged(owner.toString(), "streamer", "LINKED", "jeb_"))))
                .containsExactly(new MinecraftSyncEvent("streamer", "LINKED", "jeb_"));
        assertThat(ChannelEventRedisBridge.events(channel, envelope(ChannelEvents.MINECRAFT_LINK_CHANGED,
                new ChannelEvents.MinecraftLinkChanged(owner.toString(), "streamer", "NONE", null))))
                .containsExactly(new MinecraftDisconnectedEvent("streamer"));
    }

    @Test
    void ignoresUnknownEvents_otherChannels_andGarbage() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChannelEventRedisBridge.forward(publisher, channel, "{\"name\":\"something.else\",\"data\":{\"channelLogin\":\"s\"}}");
        ChannelEventRedisBridge.forward(publisher, "catapult:events:twitchat:" + owner,
                "{\"name\":\"bot.toggled\",\"data\":{\"channelLogin\":\"s\",\"enabled\":true}}");
        ChannelEventRedisBridge.forward(publisher, ChannelEvents.REDIS_PREFIX + "not-a-uuid",
                "{\"name\":\"bot.toggled\",\"data\":{\"channelLogin\":\"s\",\"enabled\":true}}");
        ChannelEventRedisBridge.forward(publisher, channel, "{oops");
        ChannelEventRedisBridge.forward(publisher, channel, "{\"name\":\"bot.toggled\"}");
        // No login to route it by: catapult-web keys its streams by login.
        ChannelEventRedisBridge.forward(publisher, channel, "{\"name\":\"bot.toggled\",\"data\":{\"enabled\":true}}");
        ChannelEventRedisBridge.forward(publisher, channel,
                "{\"name\":\"binding.upserted\",\"data\":{\"channelLogin\":\"s\"}}");
        verifyNoInteractions(publisher);
    }

    private final ChannelEventRedisBridge bridge = new ChannelEventRedisBridge();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private LettuceConnectionFactory redis;
    private RedisMessageListenerContainer listener;

    /** This instance's listener on {@code redis}, started the way its context would start it. */
    private void listen(LettuceConnectionFactory redis) throws Exception {
        this.redis = redis;
        listener = TestRedis.prepared(bridge.channelEventListener(redis, events));
        bridge.startChannelEventListener(listener).run(new DefaultApplicationArguments());
    }

    @AfterEach
    void stopListening() throws Exception {
        if (listener != null) listener.destroy();
        if (redis != null) redis.destroy();
    }

    @Test
    void throughRedis_anOwnersChannelEvent_reachesThisInstancesPages() throws Exception {
        listen(TestRedis.up());

        new StringRedisTemplate(redis).convertAndSend(channel, envelope(ChannelEvents.STREAM_STATE_CHANGED,
                new ChannelEvents.StreamStateChanged(owner.toString(), "Streamer", true)));

        verify(events, timeout(5_000)).publishEvent(new ChannelLiveStateEvent("streamer", true));
    }

    @Test
    void throughRedis_garbage_andTheOtherEventChannels_reachNoPage() throws Exception {
        listen(TestRedis.up());
        StringRedisTemplate publisher = new StringRedisTemplate(redis);
        String toggled = envelope(ChannelEvents.BOT_TOGGLED, new ChannelEvents.BotToggled(owner.toString(), "s", true));

        publisher.convertAndSend(channel, "{oops");
        publisher.convertAndSend(ChannelEvents.REDIS_PREFIX + "not-a-uuid", toggled);
        publisher.convertAndSend("catapult:events:user:" + owner, toggled);
        publisher.convertAndSend(channel, toggled);

        verify(events, timeout(5_000)).publishEvent(new BotStateChangedEvent("s", true));
        verify(events, after(300).times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aRedisDownAtBoot_costsTheLiveEvents_notTheBoot() {
        redis = TestRedis.down();
        listener = TestRedis.prepared(bridge.channelEventListener(redis, events));

        assertThatCode(() -> bridge.startChannelEventListener(listener).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        verify(events, never()).publishEvent(any(Object.class));
    }
}
