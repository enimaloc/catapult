package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserFlagRepository extends JpaRepository<UserFlag, UUID> {
    List<UserFlag> findByUser(UserAccount user);
    Optional<UserFlag> findByUserAndFlagKey(UserAccount user, String flagKey);

    /** Every (key, value) pair some member carries, once, ordered by key then value; the value is null for a bare tag. */
    @Query("select distinct f.flagKey, f.flagValue from UserFlag f order by f.flagKey, f.flagValue")
    List<Object[]> findDistinctKeyValues();
}
