package fr.enimaloc.catapult.repository.ankama;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnkamaFriendLinkRepository extends JpaRepository<AnkamaFriendLink, UUID> {
    Optional<AnkamaFriendLink> findByUser(UserAccount user);

    Optional<AnkamaFriendLink> findByUserId(UUID userId);

    /** With the service account loaded: open-in-view is disabled, readers outside a transaction need it. */
    @EntityGraph(attributePaths = "serviceAccount")
    Optional<AnkamaFriendLink> findWithServiceAccountByUser(UserAccount user);

    List<AnkamaFriendLink> findByServiceAccountId(UUID serviceAccountId);

    Optional<AnkamaFriendLink> findByServiceAccountIdAndAnkamaUserId(UUID serviceAccountId, long ankamaUserId);

    List<AnkamaFriendLink> findByServiceAccountIdAndStatusIn(UUID serviceAccountId,
                                                             Collection<AnkamaFriendLink.Status> statuses);

    long countByServiceAccountIdAndStatusIn(UUID serviceAccountId, Collection<AnkamaFriendLink.Status> statuses);

    /** Another user's link on the same Ankama name#tag (case-insensitive), whatever status but {@code excluded}. */
    boolean existsByUserIdNotAndStatusNotAndRequestedNameIgnoreCaseAndRequestedTag(
            UUID userId, AnkamaFriendLink.Status excluded, String requestedName, String requestedTag);

    boolean existsByUserIdNotAndStatusNotAndAnkamaUserId(UUID userId, AnkamaFriendLink.Status excluded,
                                                         long ankamaUserId);

    List<AnkamaFriendLink> findByAnkamaUserIdAndStatus(long ankamaUserId, AnkamaFriendLink.Status status);
}
