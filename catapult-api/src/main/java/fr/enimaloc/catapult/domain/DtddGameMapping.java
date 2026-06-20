package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "dtdd_game_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DtddGameMapping {

    @Id
    @Column(name = "igdb_id", nullable = false, length = 64)
    private String igdbId;

    @Column(name = "dtdd_id")
    private Long dtddId;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;
}
