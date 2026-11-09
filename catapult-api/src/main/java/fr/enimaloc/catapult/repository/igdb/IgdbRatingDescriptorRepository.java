package fr.enimaloc.catapult.repository.igdb;

import fr.enimaloc.catapult.domain.igdb.IgdbRatingDescriptor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IgdbRatingDescriptorRepository extends JpaRepository<IgdbRatingDescriptor, Long> {

    List<IgdbRatingDescriptor> findByDescription(String description);
}
