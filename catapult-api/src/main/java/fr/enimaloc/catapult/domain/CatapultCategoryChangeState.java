package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catapult_category_change_state")
@Getter
@Setter
public class CatapultCategoryChangeState {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "game_id")
    private String gameId;

    @Column(name = "previous_game_id")
    private String previousGameId;

    @Column(name = "applied_at")
    private Instant appliedAt;
}
