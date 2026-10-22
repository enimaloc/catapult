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
 * Sends the pending deliveries (NotificationDeliveryService) through their channel, oldest first:
 * Twitchat ones when catapult-web flushes them (it alone knows when the member's Twitchat page or
 * OBS widget is connected), chat ones at the member's next stream start. Each is claimed (its row
 * deleted, atomically) right before it is sent, so concurrent flushes, a repeated stream start or a
 * read in between never send it twice or after the fact; a failed send puts it back. Expired ones
 * are claimed and dropped unsent.
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
    private final TwitchatWidgetSettingsService widgetSettings;
    private final TwitchChatService chatService;
    private final TaskScheduler scheduler;
    private final NotificationRenderer renderer;

    public NotificationDeliveryDispatcher(NotificationDeliveryRepository deliveryRepo,
                                          TwitchatEventDispatcher twitchatDispatcher,
                                          TwitchatWidgetSettingsService widgetSettings,
                                          TwitchChatService chatService,
                                          TaskScheduler scheduler,
                                          NotificationRenderer renderer) {
        this.deliveryRepo = deliveryRepo;
        this.twitchatDispatcher = twitchatDispatcher;
        this.widgetSettings = widgetSettings;
        this.chatService = chatService;
        this.scheduler = scheduler;
        this.renderer = renderer;
    }

    /**
     * Publishes the member's pending Twitchat notifications and returns how many reached Twitchat.
     * With the Twitchat widget disabled nothing could reach it: they all stay pending. One the
     * member has no enabled payload for is dropped (it would never go out).
     */
    public int flushTwitchat(UserAccount user) {
        if (!widgetSettings.isEnabled(user)) {
            return 0;
        }
        Instant now = Instant.now();
        int delivered = 0;
        for (NotificationDelivery delivery : deliveryRepo.findPending(user.getId(), NotificationChannel.TWITCHAT)) {
            Notification n = delivery.getNotification();
            if (!claim(delivery) || expired(n, now)) {
                continue;
            }
            try {
                if (twitchatDispatcher.onNotification(user, n.getTitle(), renderer.toPlainText(n.getBody()),
                        nullToEmpty(n.getCtaUrl()), nullToEmpty(n.getCtaLabel()))) {
                    delivered++;
                }
            } catch (RuntimeException e) {
                log.warn("Twitchat notification {} not delivered to {}: {}", n.getId(), user.getId(), e.getMessage(), e);
                restore(delivery);
            }
        }
        return delivered;
    }

    /**
     * Schedules the member's pending chat notifications, spaced out (never blocking the EventSub
     * thread this runs on); each is claimed only when its turn comes. With the bot disabled they
     * stay pending for a later stream.
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
                if (expired(delivery.getNotification(), now)) {
                    claim(delivery);
                    continue;
                }
                scheduler.schedule(() -> post(user, delivery), now.plusSeconds(CHAT_SPACING_SECONDS * slot++));
            }
        } catch (RuntimeException e) {
            log.warn("Stream-start notifications not posted for {}: {}", user.getId(), e.getMessage(), e);
        }
    }

    private void post(UserAccount user, NotificationDelivery delivery) {
        Notification n = delivery.getNotification();
        try {
            if (!claim(delivery) || expired(n, Instant.now())) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Notification {} not claimed for {}'s chat: {}", n.getId(), user.getId(), e.getMessage(), e);
            return;
        }
        try {
            chatService.sendMessage(user, chatText(n));
        } catch (RuntimeException e) {
            log.warn("Notification {} not posted in {}'s chat: {}", n.getId(), user.getId(), e.getMessage(), e);
            restore(delivery);
        }
    }

    /** Whether this caller took the delivery: false when someone else did, or it was read or cancelled. */
    private boolean claim(NotificationDelivery delivery) {
        NotificationDeliveryId id = delivery.getId();
        return deliveryRepo.claim(id.getNotificationId(), id.getUserId(), id.getChannel()) == 1;
    }

    private void restore(NotificationDelivery delivery) {
        NotificationDeliveryId id = delivery.getId();
        try {
            deliveryRepo.restore(id.getNotificationId(), id.getUserId(), id.getChannel().name(), delivery.getCreatedAt());
        } catch (RuntimeException e) {
            log.warn("Notification {} could not be put back pending for {}: {}", id.getNotificationId(), id.getUserId(),
                    e.getMessage(), e);
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
