package fr.enimaloc.catapult.repository.epic;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EpicFriendLinkRepository extends JpaRepository<EpicFriendLink, UUID> {
    Optional<EpicFriendLink> findByUser(UserAccount user);

    /** With the service account loaded: open-in-view is disabled, readers outside a transaction need it. */
    @EntityGraph(attributePaths = "serviceAccount")
    Optional<EpicFriendLink> findWithServiceAccountByUser(UserAccount user);

    Optional<EpicFriendLink> findByUserId(UUID userId);

    /** The Epic account's links in any other status: unique among non-REMOVED rows only, so possibly several. */
    List<EpicFriendLink> findByEpicAccountIdAndStatusNot(String epicAccountId, EpicFriendLink.Status status);

    List<EpicFriendLink> findByEpicAccountIdAndStatus(String epicAccountId, EpicFriendLink.Status status);

    Optional<EpicFriendLink> findByEpicAccountId(String epicAccountId);

    List<EpicFriendLink> findByServiceAccountId(UUID serviceAccountId);

    List<EpicFriendLink> findByServiceAccountIdAndStatusIn(UUID serviceAccountId,
                                                           Collection<EpicFriendLink.Status> statuses);

    long countByServiceAccountIdAndStatusIn(UUID serviceAccountId, Collection<EpicFriendLink.Status> statuses);

    long countByStatus(EpicFriendLink.Status status);
}
