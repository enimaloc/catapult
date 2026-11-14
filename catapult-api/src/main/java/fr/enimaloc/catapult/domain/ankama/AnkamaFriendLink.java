package fr.enimaloc.catapult.domain.ankama;

import fr.enimaloc.catapult.domain.account.UserAccount;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ankama_friend_link")
@Getter
@Setter
public class AnkamaFriendLink {

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
    private AnkamaServiceAccount serviceAccount;

    /** Ankama nickname the streamer asked to be befriended under. */
    @Column(nullable = false)
    private String requestedName;

    @Column(nullable = false)
    private String requestedTag;

    /** Ankama user id of the streamer, once the friend request resolved it. */
    private Long ankamaUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant acceptedAt;

    public String displayTag() {
        return requestedName + "#" + requestedTag;
    }
}
