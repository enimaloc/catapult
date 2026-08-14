package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "twitchat_payload_preset")
@Getter
@Setter
public class TwitchatPayloadPreset {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private TwitchatNotificationEventType eventType;

    @Column(nullable = false)
    private String name;

    @Column(name = "payload_json", columnDefinition = "text", nullable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
