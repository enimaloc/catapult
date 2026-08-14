package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "twitchat_action_token")
@Getter
@Setter
public class TwitchatActionToken {

    @Id
    private UUID token;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private TwitchatActionType actionType;

    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
