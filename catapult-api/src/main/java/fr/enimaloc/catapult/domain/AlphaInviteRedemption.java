package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alpha_invite_redemption")
@Getter
@Setter
@NoArgsConstructor
public class AlphaInviteRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invite_id", nullable = false)
    private AlphaInvite invite;

    @Column(name = "invitee_twitch_id", nullable = false, length = 50)
    private String inviteeTwitchId;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt = Instant.now();
}
