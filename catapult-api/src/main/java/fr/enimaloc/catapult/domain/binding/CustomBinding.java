package fr.enimaloc.catapult.domain.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A binding a streamer creates and triggers by hand, tied to no game: a Twitch category, CCLs and TWs. */
@Entity
@Table(name = "custom_binding")
@Getter
@Setter
public class CustomBinding {

    /** {@link GameBinding#getSourceName()} prefix of the transient binding a custom binding is applied through. */
    public static final String SOURCE_NAME_PREFIX = "custom:";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(name = "twitch_game_id", nullable = false)
    private String twitchGameId;

    @Column(name = "twitch_game_name", nullable = false)
    private String twitchGameName;

    /** When set, no detected game interrupts it: only a deactivation or the end of the stream does. */
    @Column(nullable = false)
    private boolean uninterruptible = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "custom_binding_ccl", joinColumns = @JoinColumn(name = "custom_binding_id"))
    @Column(name = "ccl")
    private Set<String> ccls = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "custom_binding_tw", joinColumns = @JoinColumn(name = "custom_binding_id"))
    @Column(name = "tw")
    private Set<String> tws = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The transient (never saved) {@link GameBinding} this binding is sent to Twitch through, like the detection's
     * fallbacks. Here rather than in a service so the activation and the detection path build it alike, without
     * depending on each other.
     */
    public GameBinding toGameBinding(UserAccount owner) {
        GameBinding applied = new GameBinding();
        applied.setUser(owner);
        applied.setSourceType(GameBinding.SourceType.MANUAL);
        applied.setStatus(GameBinding.Status.MANUAL);
        applied.setSourceName(SOURCE_NAME_PREFIX + name);
        applied.setTwitchGameId(twitchGameId);
        applied.setTwitchGameName(twitchGameName);
        applied.getCcls().addAll(ccls);
        applied.getTws().addAll(tws);
        return applied;
    }
}
