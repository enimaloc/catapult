package fr.enimaloc.catapult.repository.battlenet;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BattleNetFriendLinkRepository extends JpaRepository<BattleNetFriendLink, UUID> {
    Optional<BattleNetFriendLink> findByUser(UserAccount user);

    /**
     * Variant with the service account loaded (open-in-view is disabled: readers
     * outside a transaction, such as controllers, cannot initialize the LAZY proxy).
     */
    @EntityGraph(attributePaths = "serviceAccount")
    Optional<BattleNetFriendLink> findWithServiceAccountByUser(UserAccount user);
    List<BattleNetFriendLink> findByServiceAccount(BattleNetServiceAccount serviceAccount);
    long countByServiceAccount(BattleNetServiceAccount serviceAccount);
    long countByServiceAccountAndStatus(BattleNetServiceAccount serviceAccount, BattleNetFriendLink.Status status);
    boolean existsByServiceAccountAndStatus(BattleNetServiceAccount serviceAccount, BattleNetFriendLink.Status status);

    /**
     * Several links can match (nothing makes {@code bnetAccountId} unique per bot: two Catapult users may link the
     * same Battle.net account), hence a list.
     */
    List<BattleNetFriendLink> findByServiceAccountAndBnetAccountId(BattleNetServiceAccount serviceAccount,
                                                                   long bnetAccountId);

    /**
     * Attaches InvitationSent events, which carry account_id 0: matched by BattleTag instead. Several links can
     * match (no unique constraint), hence a list.
     */
    List<BattleNetFriendLink> findByServiceAccountAndBattleTagIgnoreCase(BattleNetServiceAccount serviceAccount,
                                                                         String battleTag);

    List<BattleNetFriendLink> findByServiceAccountAndInvitationId(BattleNetServiceAccount serviceAccount,
                                                                  long invitationId);
}
