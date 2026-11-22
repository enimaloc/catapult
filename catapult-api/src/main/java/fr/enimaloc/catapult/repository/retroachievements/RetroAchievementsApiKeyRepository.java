package fr.enimaloc.catapult.repository.retroachievements;

import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RetroAchievementsApiKeyRepository extends JpaRepository<RetroAchievementsApiKeyEntry, String> {
    List<RetroAchievementsApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM RetroAchievementsApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<RetroAchievementsApiKeyEntry> findByExclusiveFalseWithOwner();
}
