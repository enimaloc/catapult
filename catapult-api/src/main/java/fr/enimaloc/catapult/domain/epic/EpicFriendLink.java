package fr.enimaloc.catapult.domain.epic;

import fr.enimaloc.catapult.domain.account.UserAccount;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "epic_friend_link")
@Getter
@Setter
public class EpicFriendLink {

    public enum Status {
        PENDING, ACCEPTED, REMOVED, INVITE_REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_account_id", nullable = false)
    private EpicServiceAccount serviceAccount;

    /** Epic account id of the streamer. */
    @Column(nullable = false)
    private String epicAccountId;

    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant acceptedAt;
}
