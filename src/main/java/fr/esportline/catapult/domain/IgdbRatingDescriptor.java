package fr.esportline.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "igdb_rating_descriptor")
@Getter
@Setter
public class IgdbRatingDescriptor {

    /** IGDB's own stable ID (e.g. 29 = "Violence" for ESRB). */
    @Id
    private Long id;

    private String description;

    @Column(name = "organization_id")
    private Integer organizationId;

    @Column(name = "display_name", nullable = false)
    private String displayName;
}
