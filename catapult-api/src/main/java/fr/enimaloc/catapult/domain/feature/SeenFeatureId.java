package fr.enimaloc.catapult.domain.feature;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SeenFeatureId implements Serializable {
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "feature_key", length = 100)
    private String featureKey;
}
