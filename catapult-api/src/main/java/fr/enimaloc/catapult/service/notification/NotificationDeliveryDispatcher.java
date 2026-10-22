package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.domain.notification.NotificationDelivery;
import fr.enimaloc.catapult.domain.notification.NotificationDeliveryId;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.notification.NotificationDeliveryRepository;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Sends the pending deliveries (NotificationDeliveryService) through their channel, oldest first,
 * deleting each once sent and the expired ones unsent: Twitchat ones when catapult-web flushes them
 * (it alone knows when the member's Twitchat page or OBS widget is connected), chat ones at the
 * member's next stream start. Each row goes in its own transaction, so a failure only keeps its own.
 */
@Slf4j
@Component
public class NotificationDeliveryDispatcher {

    /** Twitch's limit for one chat message. */
    static final int CHAT_MAX = 500;
    /** Between two chat posts, to stay clear of Twitch's rate limit and not flood the chat. */
    static final long CHAT_SPACING_SECONDS = 3;

    private final NotificationDeliveryRepository deliveryRepo;
    private final TwitchatEventDispatcher twitchatDispatcher;
    private final TwitchChatService chatService;
    private final TaskScheduler scheduler;
    private final NotificationRenderer renderer;

    public NotificationDeliveryDispatcher(NotificationDeliveryRepository deliveryRepo,
                                          TwitchatEventDispatcher twitchatDispatcher,
                                          TwitchChatService chatService,
                                          TaskScheduler scheduler,
                                          NotificationRenderer renderer) {
        this.deliveryRepo = deliveryRepo;
        this.twitchatDispatcher = twitchatDispatcher;
        this.chatService = chatService;
        this.scheduler = scheduler;
        this.renderer = renderer;
    }

    /**
     * Publishes the member's pending Twitchat notifications and returns how many reached Twitchat.
     * Each is deleted once handed over, even when the member has no enabled payload for it (it
     * would never go out); a failed one stays for the next flush.
     */
    public int flushTwitchat(UserAccount user) {
        Instant now = Instant.now();
        int delivered = 0;
        for (NotificationDelivery delivery : deliveryRepo.findPending(user.getId(), NotificationChannel.TWITCHAT)) {
            Notification n = delivery.getNotification();
            if (expired(n, now)) {
                deliveryRepo.deleteById(delivery.getId());
                continue;
            }
            try {
                if (twitchatDispatcher.onNotification(user, n.getTitle(), renderer.toPlainText(n.getBody()),
                        nullToEmpty(n.getCtaUrl()), nullToEmpty(n.getCtaLabel()))) {
                    delivered++;
                }
                deliveryRepo.deleteById(delivery.getId());
            } catch (RuntimeException e) {
                log.warn("Twitchat notification {} not delivered to {}: {}", n.getId(), user.getId(), e.getMessage(), e);
            }
        }
        return delivered;
    }

    /**
     * Posts the member's pending chat notifications, spaced out on the scheduler (never blocking the
     * EventSub thread this runs on). With the bot disabled they stay pending for a later stream.
     */
    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        if (!user.isBotEnabled()) {
            return;
        }
        try {
            List<NotificationDelivery> pending = deliveryRepo.findPending(user.getId(), NotificationChannel.STREAM_START_CHAT);
            Instant now = Instant.now();
            int slot = 0;
            for (NotificationDelivery delivery : pending) {
                Notification n = delivery.getNotification();
                if (expired(n, now)) {
                    deliveryRepo.deleteById(delivery.getId());
                    continue;
                }
                String text = chatText(n);
                NotificationDeliveryId id = delivery.getId();
                scheduler.schedule(() -> post(user, id, text), now.plusSeconds(CHAT_SPACING_SECONDS * slot++));
            }
        } catch (RuntimeException e) {
            log.warn("Stream-start notifications not posted for {}: {}", user.getId(), e.getMessage(), e);
        }
    }

    private void post(UserAccount user, NotificationDeliveryId id, String text) {
        try {
            chatService.sendMessage(user, text);
            deliveryRepo.deleteById(id);
        } catch (RuntimeException e) {
            log.warn("Notification {} not posted in {}'s chat: {}", id.getNotificationId(), user.getId(), e.getMessage(), e);
        }
    }

    /** {@code [Catapult] title — body link}, cut to Twitch's 500 characters. */
    String chatText(Notification n) {
        String cta = n.getCtaUrl() == null || n.getCtaUrl().isBlank() ? "" : " " + n.getCtaUrl();
        String text = "[Catapult] " + n.getTitle() + " — " + renderer.toPlainText(n.getBody()) + cta;
        if (text.length() <= CHAT_MAX) {
            return text;
        }
        int cut = CHAT_MAX - 1;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        return text.substring(0, cut) + "…";
    }

    private static boolean expired(Notification n, Instant now) {
        return n.getExpiresAt() != null && !n.getExpiresAt().isAfter(now);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
