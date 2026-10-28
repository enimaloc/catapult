package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Admin listing order: username ascending with the nameless last, then id for a stable page boundary. */
    String MEMBERS_ORDER = " order by case when u.twitchUsername is null then 1 else 0 end, u.twitchUsername, u.id";

    String MEMBERS_MATCH = " where lower(u.twitchUsername) like :pattern escape '\\' or u.twitchId = :twitchId";

    /** One page of every member, DB-side. */
    @Query(value = "select u from UserAccount u" + MEMBERS_ORDER, countQuery = "select count(u) from UserAccount u")
    Page<UserAccount> findMembersPage(Pageable pageable);

    /**
     * One page of the members whose username contains the (already lower-cased and escaped, see
     * likePattern) pattern, or whose twitch id is exactly {@code twitchId}.
     */
    @Query(value = "select u from UserAccount u" + MEMBERS_MATCH + MEMBERS_ORDER,
            countQuery = "select count(u) from UserAccount u" + MEMBERS_MATCH)
    Page<UserAccount> searchMembersPage(@Param("pattern") String pattern, @Param("twitchId") String twitchId,
                                        Pageable pageable);

    /** Same match as searchMembersPage, no count: for a picker that only wants the first few. */
    @Query("select u from UserAccount u" + MEMBERS_MATCH + MEMBERS_ORDER)
    List<UserAccount> searchMembers(@Param("pattern") String pattern, @Param("twitchId") String twitchId,
                                    Pageable pageable);

    /** A substring LIKE pattern for the text, lower-cased, with LIKE's own wildcards and the escape taken literally. */
    static String likePattern(String text) {
        return "%" + text.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
