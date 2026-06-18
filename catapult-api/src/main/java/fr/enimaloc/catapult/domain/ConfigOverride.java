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

    public static final String DEFAULT_MODULE = "api";

    @EmbeddedId
    private ConfigOverrideId id = new ConfigOverrideId(DEFAULT_MODULE, null);

    @Column(name = "value")
    private String value;

    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    public String getKey() {
        return id == null ? null : id.getKey();
    }

    public void setKey(String key) {
        if (id == null) {
            id = new ConfigOverrideId(DEFAULT_MODULE, key);
        } else {
            id.setKey(key);
        }
    }

    public String getModule() {
        return id == null ? null : id.getModule();
    }

    public void setModule(String module) {
        if (id == null) {
            id = new ConfigOverrideId(module, null);
        } else {
            id.setModule(module);
        }
    }
}
