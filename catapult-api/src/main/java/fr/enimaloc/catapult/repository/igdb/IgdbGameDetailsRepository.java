package fr.enimaloc.catapult.repository.igdb;

import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IgdbGameDetailsRepository extends JpaRepository<IgdbGameDetails, String> {
}
