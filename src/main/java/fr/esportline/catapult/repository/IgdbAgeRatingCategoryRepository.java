package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbAgeRatingCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IgdbAgeRatingCategoryRepository extends JpaRepository<IgdbAgeRatingCategory, Long> {

    Optional<IgdbAgeRatingCategory> findByCategoryIdAndRating(Integer categoryId, Integer rating);
}
