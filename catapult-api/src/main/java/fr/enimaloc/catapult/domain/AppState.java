package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "app_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppState {

    @Id
    @Column(name = "state_key", length = 80)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
