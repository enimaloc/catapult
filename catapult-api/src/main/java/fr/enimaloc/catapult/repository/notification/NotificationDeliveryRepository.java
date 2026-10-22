package fr.enimaloc.catapult.repository.notification;

import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.domain.notification.NotificationDelivery;
import fr.enimaloc.catapult.domain.notification.NotificationDeliveryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, NotificationDeliveryId> {

    /**
     * One pending delivery per recipient of the notification and channel they opted in to, in a
     * single statement: a broadcast can have thousands of recipients. Flushes first so that the
     * recipient rows just saved are there to select.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO notification_delivery (notification_id, user_id, channel, created_at)
            SELECT r.notification_id, r.user_id, p.channel, :now
            FROM notification_recipient r
            JOIN notification_channel_preference p ON p.user_id = r.user_id
            WHERE r.notification_id = :notificationId""", nativeQuery = true)
    int createForNotification(@Param("notificationId") UUID notificationId, @Param("now") Instant now);

    @Query("select d.id.userId from NotificationDelivery d where d.id.notificationId = :notificationId and d.id.channel = :channel")
    List<UUID> findUserIdsByNotificationAndChannel(@Param("notificationId") UUID notificationId,
                                                   @Param("channel") NotificationChannel channel);

    /** A member's pending deliveries through {@code channel}, with their notification, oldest first. */
    @Query("""
            select d from NotificationDelivery d join fetch d.notification n
            where d.id.userId = :userId and d.id.channel = :channel
            order by d.createdAt asc, n.createdAt asc""")
    List<NotificationDelivery> findPending(@Param("userId") UUID userId, @Param("channel") NotificationChannel channel);

    @Modifying
    @Query("delete from NotificationDelivery d where d.id.userId = :userId and d.id.notificationId = :notificationId")
    int deleteByUserAndNotification(@Param("userId") UUID userId, @Param("notificationId") UUID notificationId);

    @Modifying
    @Query("delete from NotificationDelivery d where d.id.userId = :userId")
    int deleteByUser(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from NotificationDelivery d where d.id.userId = :userId and d.id.channel in :channels")
    int deleteByUserAndChannels(@Param("userId") UUID userId, @Param("channels") List<NotificationChannel> channels);

    /**
     * Takes one delivery for sending, atomically: 1 for the caller that removed the row, 0 for any
     * other (a concurrent flush, a second stream start, a read or a cancel got there first). Its own
     * transaction, so it is visible to the others at once.
     */
    @Transactional
    @Modifying
    @Query("""
            delete from NotificationDelivery d
            where d.id.notificationId = :notificationId and d.id.userId = :userId and d.id.channel = :channel""")
    int claim(@Param("notificationId") UUID notificationId, @Param("userId") UUID userId,
              @Param("channel") NotificationChannel channel);

    /** Puts back a claimed delivery whose send failed, as it was; none when its notification is gone meanwhile. */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO notification_delivery (notification_id, user_id, channel, created_at)
            SELECT n.id, :userId, :channel, :createdAt FROM notification n WHERE n.id = :notificationId""", nativeQuery = true)
    int restore(@Param("notificationId") UUID notificationId, @Param("userId") UUID userId,
                @Param("channel") String channel, @Param("createdAt") Instant createdAt);
}
