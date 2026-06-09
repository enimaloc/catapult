package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
public class SystemSetting {

    @Id
    @Column(nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String value;
}
