package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbGameAgeRating;
import fr.esportline.catapult.domain.IgdbGameAgeRatingPk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IgdbGameAgeRatingRepository extends JpaRepository<IgdbGameAgeRating, IgdbGameAgeRatingPk> {

    List<IgdbGameAgeRating> findByIgdbId(String igdbId);
}
