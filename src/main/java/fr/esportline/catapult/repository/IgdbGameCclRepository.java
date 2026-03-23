package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbGameCcl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;

public interface IgdbGameCclRepository extends JpaRepository<IgdbGameCcl, String> {

    @Query("SELECT c.igdbId FROM IgdbGameCcl c")
    Set<String> findAllIgdbIds();
}
