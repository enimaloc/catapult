package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "config_override")
@Getter
@Setter
public class ConfigOverride {
    @Id
    @Column(name = "key", nullable = false, unique = true)
    private String key;

    @Column(name = "value")
    private String value;

    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;
}
