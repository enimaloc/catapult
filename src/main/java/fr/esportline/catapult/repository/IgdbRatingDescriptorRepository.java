package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbRatingDescriptor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IgdbRatingDescriptorRepository extends JpaRepository<IgdbRatingDescriptor, Long> {

    List<IgdbRatingDescriptor> findByDescription(String description);
}
