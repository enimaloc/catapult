package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "process_binding")
@Getter
@Setter
public class ProcessBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "process_name", nullable = false)
    private String processName;

    @Column(name = "is_regex", nullable = false)
    private boolean regex = false;

    @Column(name = "twitch_game_id")
    private String twitchGameId;

    @Column(name = "twitch_game_name")
    private String twitchGameName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "binding", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC, id ASC")
    private List<ProcessPredicate> predicates = new ArrayList<>();

    public boolean isGlobal() {
        return user == null;
    }
}
