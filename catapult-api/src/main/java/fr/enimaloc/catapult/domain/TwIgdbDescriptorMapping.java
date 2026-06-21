package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tw_igdb_descriptor_mapping",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tw_id", "descriptor_id"}))
@Getter
@Setter
public class TwIgdbDescriptorMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tw_id", nullable = false, length = 40)
    private String twId;

    @Column(name = "descriptor_id", nullable = false)
    private Long descriptorId;
}
