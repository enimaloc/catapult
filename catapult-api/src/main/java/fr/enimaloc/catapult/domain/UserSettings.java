package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;
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

    @Column(name = "tw_feature_enabled", nullable = false)
    private boolean twFeatureEnabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_blocked_tws",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "tw_id")
    private Set<String> blockedTws = new HashSet<>();

    @Column(name = "no_game_twitch_game_id")
    private String noGameTwitchGameId;

    @Column(name = "no_game_twitch_game_name")
    private String noGameTwitchGameName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_no_game_ccls",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "ccl_id")
    private Set<String> noGameCcls = new HashSet<>();

    @Column(name = "incomplete_fallback_twitch_game_id")
    private String incompleteFallbackTwitchGameId;

    @Column(name = "incomplete_fallback_twitch_game_name")
    private String incompleteFallbackTwitchGameName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_incomplete_fallback_ccls",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "ccl_id")
    private Set<String> incompleteFallbackCcls = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_blocked_ccls",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "ccl_id")
    private Set<String> blockedCcls = new HashSet<>(Set.of("Gambling"));

    @Column(name = "apply_default_on_stream_start", nullable = false)
    private boolean applyDefaultOnStreamStart = true;

    @Column(name = "apply_default_on_no_game", nullable = false)
    private boolean applyDefaultOnNoGame = true;

    @Column(name = "apply_default_on_stream_end", nullable = false)
    private boolean applyDefaultOnStreamEnd = true;
}
