package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "twitchat_active_preset")
@IdClass(TwitchatActivePreset.Key.class)
@Getter
@Setter
public class TwitchatActivePreset {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type")
    private TwitchatNotificationEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preset_id", nullable = false)
    private TwitchatPayloadPreset preset;

    public static class Key implements Serializable {
        private UUID userId;
        private TwitchatNotificationEventType eventType;

        public Key() {
        }

        public Key(UUID userId, TwitchatNotificationEventType eventType) {
            this.userId = userId;
            this.eventType = eventType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(userId, key.userId) && eventType == key.eventType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, eventType);
        }
    }
}
