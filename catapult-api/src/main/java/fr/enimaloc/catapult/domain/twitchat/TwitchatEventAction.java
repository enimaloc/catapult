package fr.enimaloc.catapult.domain.twitchat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One payload sent to Twitchat on a Catapult event: a catalog action and its data (see V79). */
@Entity
@Table(name = "twitchat_event_action")
@Getter
@Setter
public class TwitchatEventAction {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private TwitchatNotificationEventType eventType;

    @Column(nullable = false)
    private int position;

    /** A key of catapult-common's TwitchatCatalog. */
    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TwitchatPayloadMode mode;

    /** The action's data, in both modes: the form's fields are paths into it. */
    @Column(name = "data_json", columnDefinition = "text", nullable = false)
    private String dataJson;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
