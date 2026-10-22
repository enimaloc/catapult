package fr.enimaloc.catapult.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A channel a member opted in to receive notifications through, besides the site. */
@Entity
@Table(name = "notification_channel_preference")
@Getter
@Setter
@NoArgsConstructor
public class NotificationChannelPreference {

    @EmbeddedId
    private NotificationChannelPreferenceId id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public NotificationChannelPreference(NotificationChannelPreferenceId id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }
}
