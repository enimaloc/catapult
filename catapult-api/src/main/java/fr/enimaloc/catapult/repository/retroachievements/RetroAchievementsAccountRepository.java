package fr.enimaloc.catapult.repository.retroachievements;

import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' RetroAchievements accounts. */
public interface RetroAchievementsAccountRepository extends JpaRepository<RetroAchievementsAccount, UUID> {
}
