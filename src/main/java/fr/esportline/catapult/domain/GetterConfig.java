package fr.esportline.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "getter_config",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider"})
)
@Getter
@Setter
public class GetterConfig {

    public enum Provider {
        STEAM, XBOX, BATTLENET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;
}
