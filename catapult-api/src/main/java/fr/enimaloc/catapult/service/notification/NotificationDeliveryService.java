package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreference;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreferenceId;
import fr.enimaloc.catapult.repository.notification.NotificationChannelPreferenceRepository;
import fr.enimaloc.catapult.repository.notification.NotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The channels each member also receives notifications through, besides the site, and the
 * deliveries still pending on them: created when a notification is sent, cancelled when it is
 * read or the channel dropped. Sending them is NotificationDeliveryDispatcher's.
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private final NotificationChannelPreferenceRepository preferenceRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final TwitchatEventActionService eventActionService;

    public Set<NotificationChannel> channels(UUID userId) {
        return preferenceRepo.findByIdUserId(userId).stream()
                .map(p -> p.getId().getChannel())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationChannel.class)));
    }

    /** Replaces the member's channels; a dropped one takes its pending deliveries with it. */
    @Transactional
    public void replaceChannels(UserAccount user, Set<NotificationChannel> wanted) {
        UUID userId = user.getId();
        Set<NotificationChannel> current = channels(userId);
        List<NotificationChannel> removed = current.stream().filter(c -> !wanted.contains(c)).toList();
        List<NotificationChannel> added = wanted.stream().filter(c -> !current.contains(c)).sorted().toList();
        if (!removed.isEmpty()) {
            preferenceRepo.deleteAllById(removed.stream().map(c -> new NotificationChannelPreferenceId(userId, c)).toList());
            deliveryRepo.deleteByUserAndChannels(userId, removed);
        }
        if (!added.isEmpty()) {
            Instant now = Instant.now();
            preferenceRepo.saveAll(added.stream()
                    .map(c -> new NotificationChannelPreference(new NotificationChannelPreferenceId(userId, c), now))
                    .toList());
        }
        if (added.contains(NotificationChannel.TWITCHAT)) {
            eventActionService.ensureNotificationPayload(user);
        }
    }

    /**
     * The pending deliveries of a notification whose recipients are saved: one per recipient and
     * channel they opted in to, in one statement. Returns the members with a Twitchat one, for the
     * caller to announce to catapult-web (once per send, however many notifications it stores).
     */
    @Transactional
    public List<UUID> createFor(UUID notificationId) {
        if (deliveryRepo.createForNotification(notificationId, Instant.now()) == 0) {
            return List.of();
        }
        return deliveryRepo.findUserIdsByNotificationAndChannel(notificationId, NotificationChannel.TWITCHAT);
    }

    @Transactional
    public void cancel(UUID userId, UUID notificationId) {
        deliveryRepo.deleteByUserAndNotification(userId, notificationId);
    }

    @Transactional
    public void cancelAll(UUID userId) {
        deliveryRepo.deleteByUser(userId);
    }
}
