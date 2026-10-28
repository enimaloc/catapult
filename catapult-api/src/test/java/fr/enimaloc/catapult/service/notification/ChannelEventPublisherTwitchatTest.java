package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatDispatch;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChannelEventPublisherTwitchatTest {

    @Mock private RedisEventPublisher redisPublisher;
    @InjectMocks private ChannelEventPublisher channelEventPublisher;

    @Test
    void twitchatDispatch_delegatesToRedisPublisherOnTwitchatChannel() {
        UUID ownerId = UUID.randomUUID();
        TwitchatDispatch dispatch = new TwitchatDispatch("streamer", "STREAM_STARTED", List.of());

        channelEventPublisher.twitchatDispatch(ownerId, dispatch);

        verify(redisPublisher).publishTwitchat(ownerId, "twitchat.dispatch", dispatch);
    }

    @Test
    void twitchatWidgetSettingsUpdated_delegatesToRedisPublisherOnTwitchatChannel() {
        UUID ownerId = UUID.randomUUID();
        TwitchatWidgetConfig config = new TwitchatWidgetConfig("127.0.0.1", 4455, "s3cret", "auto");

        channelEventPublisher.twitchatWidgetSettingsUpdated(ownerId, config);

        verify(redisPublisher).publishTwitchat(ownerId, "twitchat.widget.settings.updated", config);
    }
}
