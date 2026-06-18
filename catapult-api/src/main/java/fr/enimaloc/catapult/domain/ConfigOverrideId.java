package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ConfigOverrideId implements Serializable {

    @Column(name = "module", nullable = false, length = 16)
    private String module;

    @Column(name = "key", nullable = false)
    private String key;
}
