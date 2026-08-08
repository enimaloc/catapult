package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.service.notification.dto.TwitchatAction;
import fr.enimaloc.catapult.service.notification.dto.TwitchatNotification;
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
    void twitchatNotify_delegatesToRedisPublisherOnTwitchatChannel() {
        UUID ownerId = UUID.randomUUID();
        TwitchatNotification notification = new TwitchatNotification(
                "Le bot a été activé.", "message",
                List.of(new TwitchatAction("Désactiver le bot", "url", "https://x/y", "alert")));

        channelEventPublisher.twitchatNotify(ownerId, notification);

        verify(redisPublisher).publishTwitchat(ownerId, notification);
    }
}
