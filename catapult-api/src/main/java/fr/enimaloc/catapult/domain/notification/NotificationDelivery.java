package fr.enimaloc.catapult.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A notification still to be sent to one member through one of their channels: deleted once
 * delivered or cancelled. Created in bulk by NotificationDeliveryRepository#createForNotification.
 */
@Entity
@Table(name = "notification_delivery")
@Getter
@Setter
public class NotificationDelivery {

    @EmbeddedId
    private NotificationDeliveryId id;

    @MapsId("notificationId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
