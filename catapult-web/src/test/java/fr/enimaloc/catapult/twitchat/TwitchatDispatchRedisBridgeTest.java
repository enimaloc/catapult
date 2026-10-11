package fr.enimaloc.catapult.twitchat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TwitchatDispatchRedisBridgeTest {

    private final TwitchatDispatchHub hub = mock(TwitchatDispatchHub.class);
    private final UUID owner = UUID.randomUUID();

    @Test
    void forwardsADispatch_toItsOwner_asItsDataOnly() {
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:" + owner,
                "{\"name\":\"twitchat.dispatch\",\"data\":{\"id\":\"d-1\",\"payloads\":[]},\"ts\":1}");
        verify(hub).publish(owner, "{\"id\":\"d-1\",\"payloads\":[]}");
    }

    @Test
    void ignoresOtherEvents_otherChannels_andGarbage() {
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:" + owner, "{\"name\":\"twitchat.widget.settings.updated\",\"data\":{}}");
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:channel:" + owner, "{\"name\":\"twitchat.dispatch\",\"data\":{}}");
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:not-a-uuid", "{\"name\":\"twitchat.dispatch\",\"data\":{}}");
        TwitchatDispatchRedisBridge.forward(hub, "catapult:events:twitchat:" + owner, "{oops");
        verifyNoInteractions(hub);
    }
}
