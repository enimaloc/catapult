package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.IgdbRatingDescriptor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IgdbRatingDescriptorRepository extends JpaRepository<IgdbRatingDescriptor, Long> {

    List<IgdbRatingDescriptor> findByDescription(String description);
}
