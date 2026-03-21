package fr.esportline.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "ccl_feature_enabled", nullable = false)
    private boolean cclFeatureEnabled = true;

    @Column(name = "no_game_twitch_game_id")
    private String noGameTwitchGameId;

    @Column(name = "no_game_twitch_game_name")
    private String noGameTwitchGameName;
}
