package fr.esportline.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "igdb_age_rating_category",
    uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "rating"})
)
@Getter
@Setter
public class IgdbAgeRatingCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Integer categoryId;

    @Column(nullable = false)
    private Integer rating;

    @Column(name = "display_name", nullable = false)
    private String displayName;
}
