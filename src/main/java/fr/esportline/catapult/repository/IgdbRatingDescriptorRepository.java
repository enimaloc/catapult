package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbRatingDescriptor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IgdbRatingDescriptorRepository extends JpaRepository<IgdbRatingDescriptor, Long> {
}
