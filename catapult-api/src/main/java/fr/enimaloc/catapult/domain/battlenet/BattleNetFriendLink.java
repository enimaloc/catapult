package fr.enimaloc.catapult.domain.battlenet;

import fr.enimaloc.catapult.domain.account.UserAccount;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "battlenet_friend_link")
@Getter
@Setter
public class BattleNetFriendLink {

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
    private BattleNetServiceAccount serviceAccount;

    /** BGS account id of the streamer (from OAuth userinfo.id). */
    @Column(nullable = false)
    private long bnetAccountId;

    @Column(nullable = false)
    private String battleTag;

    /** Id of the invitation sent by the bot, when known. */
    private Long invitationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant acceptedAt;
}
