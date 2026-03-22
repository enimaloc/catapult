package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbAgeRating;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IgdbAgeRatingRepository extends JpaRepository<IgdbAgeRating, Long> {
}
