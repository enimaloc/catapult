package fr.enimaloc.catapult.repository.battlenet;

import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BattleNetServiceAccountRepository extends JpaRepository<BattleNetServiceAccount, UUID> {
    List<BattleNetServiceAccount> findByEnabledTrueOrderByFillOrderAsc();

    List<BattleNetServiceAccount> findByEnabledTrueAndAuthStateOrderByFillOrderAsc(
            BattleNetServiceAccount.AuthState authState);

    long countByEnabledTrue();

    long countByEnabledTrueAndFriendLimitReachedFalse();

    long countByEnabledTrueAndAuthState(BattleNetServiceAccount.AuthState authState);

    long countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(BattleNetServiceAccount.AuthState authState);

    Optional<BattleNetServiceAccount> findByBnetAccountId(long bnetAccountId);
}
