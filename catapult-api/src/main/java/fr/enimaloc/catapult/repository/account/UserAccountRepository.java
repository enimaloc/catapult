package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTwitchId(String twitchId);

    List<UserAccount> findByTwitchIdIn(Collection<String> twitchIds);

    Optional<UserAccount> findByTwitchUsername(String twitchUsername);

    List<UserAccount> findByBotEnabledTrueAndStatus(UserAccount.Status status);

    long countByBotEnabledTrueAndStatus(UserAccount.Status status);

    List<UserAccount> findBySteamIdNotNull();

    List<UserAccount> findByStatusAndDeletionRequestedAtBefore(UserAccount.Status status, Instant cutoff);

    List<UserAccount> findByStatusAndTwitchIdNotNull(UserAccount.Status status);

    List<UserAccount> findByStatus(UserAccount.Status status);

    Optional<UserAccount> findBySystemAccountTrue();

    Optional<UserAccount> findByWidgetToken(UUID widgetToken);

    @Query("select u.widgetToken from UserAccount u where u.id = :id")
    Optional<UUID> findWidgetTokenById(@Param("id") UUID id);

    /**
     * Gives the account a widget token unless it has one already: an update of that column
     * alone, so a stale copy of the account never overwrites the rest of its row.
     *
     * @return 1 when set, 0 when the account already had a token
     */
    @Modifying(flushAutomatically = true)
    @Query("update UserAccount u set u.widgetToken = :token where u.id = :id and u.widgetToken is null")
    int setWidgetTokenIfAbsent(@Param("id") UUID id, @Param("token") UUID token);

    /** Replaces the account's widget token, that column alone (see setWidgetTokenIfAbsent). */
    @Modifying(flushAutomatically = true)
    @Query("update UserAccount u set u.widgetToken = :token where u.id = :id")
    int setWidgetToken(@Param("id") UUID id, @Param("token") UUID token);
}
