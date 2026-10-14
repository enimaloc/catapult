package fr.enimaloc.catapult.controller;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChannelEventRedisBridgeTest {

    private final NavEventStreamController navEvents = mock(NavEventStreamController.class);
    private final UUID owner = UUID.randomUUID();

    @Test
    void forwardsAStreamStateChange_toTheNavbars() {
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:channel:" + owner,
                "{\"name\":\"stream.state.changed\",\"data\":{\"channelId\":\"" + owner + "\",\"isLive\":true},\"ts\":1}");
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:channel:" + owner,
                "{\"name\":\"stream.state.changed\",\"data\":{\"isLive\":false},\"ts\":2}");

        verify(navEvents).liveChanged(owner, true);
        verify(navEvents).liveChanged(owner, false);
    }

    @Test
    void ignoresOtherEvents_otherChannels_andGarbage() {
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:channel:" + owner, "{\"name\":\"bot.toggled\",\"data\":{}}");
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:twitchat:" + owner, "{\"name\":\"stream.state.changed\",\"data\":{}}");
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:channel:not-a-uuid", "{\"name\":\"stream.state.changed\",\"data\":{}}");
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:channel:" + owner, "{oops");
        ChannelEventRedisBridge.forward(navEvents, "catapult:events:channel:" + owner, "{\"name\":\"stream.state.changed\"}");
        verifyNoInteractions(navEvents);
    }
}
