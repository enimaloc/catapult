package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.IgdbGameDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IgdbGameDetailsRepository extends JpaRepository<IgdbGameDetails, String> {
}
