package fr.esportline.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "igdb_game_age_rating")
@IdClass(IgdbGameAgeRatingPk.class)
@Getter
@NoArgsConstructor
public class IgdbGameAgeRating {

    @Id
    @Column(name = "igdb_id")
    private String igdbId;

    @Id
    @Column(name = "age_rating_id")
    private long ageRatingId;

    public IgdbGameAgeRating(String igdbId, long ageRatingId) {
        this.igdbId      = igdbId;
        this.ageRatingId = ageRatingId;
    }
}
