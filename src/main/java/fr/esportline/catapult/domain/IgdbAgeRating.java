package fr.esportline.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "igdb_age_rating")
@Getter
@NoArgsConstructor
public class IgdbAgeRating {

    /** ID IGDB de l'age rating (unique). */
    @Id
    private long id;

    /** Catégorie : 1=ESRB, 2=PEGI, 3=CERO, 4=USK, 5=GRAC, 6=CLASS_IND, 7=ACB */
    @Column(nullable = false)
    private int category;

    /** Valeur du rating (enum IGDB, varie par catégorie). */
    @Column(nullable = false)
    private int rating;

    public IgdbAgeRating(long id, int category, int rating) {
        this.id       = id;
        this.category = category;
        this.rating   = rating;
    }
}
