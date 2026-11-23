package fr.enimaloc.catapult.repository.blizzard;

import fr.enimaloc.catapult.domain.blizzard.BlizzardWowCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The World of Warcraft characters imported for the members. */
public interface BlizzardWowCharacterRepository
        extends JpaRepository<BlizzardWowCharacter, BlizzardWowCharacter.Key> {

    List<BlizzardWowCharacter> findByUserId(UUID userId);

    /** Deleted at once (a bulk delete), so that a list saved right after cannot meet the old rows' keys. */
    @Modifying(flushAutomatically = true)
    @Query("delete from BlizzardWowCharacter c where c.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
