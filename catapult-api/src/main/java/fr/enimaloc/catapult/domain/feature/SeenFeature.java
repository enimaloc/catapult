package fr.enimaloc.catapult.domain.feature;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A new feature (a catapult-web tab, by key) an account has opened, see V83. */
@Entity
@Table(name = "seen_feature")
@Getter
@Setter
@NoArgsConstructor
public class SeenFeature {
    @EmbeddedId
    private SeenFeatureId id;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;
}
