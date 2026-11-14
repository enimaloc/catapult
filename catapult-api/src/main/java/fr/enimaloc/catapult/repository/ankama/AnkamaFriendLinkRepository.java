package fr.enimaloc.catapult.repository.ankama;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnkamaFriendLinkRepository extends JpaRepository<AnkamaFriendLink, UUID> {
    Optional<AnkamaFriendLink> findByUser(UserAccount user);

    Optional<AnkamaFriendLink> findByUserId(UUID userId);

    List<AnkamaFriendLink> findByServiceAccountId(UUID serviceAccountId);

    Optional<AnkamaFriendLink> findByServiceAccountIdAndAnkamaUserId(UUID serviceAccountId, long ankamaUserId);

    List<AnkamaFriendLink> findByServiceAccountIdAndStatusIn(UUID serviceAccountId,
                                                             Collection<AnkamaFriendLink.Status> statuses);

    long countByServiceAccountIdAndStatusIn(UUID serviceAccountId, Collection<AnkamaFriendLink.Status> statuses);

    List<AnkamaFriendLink> findByAnkamaUserIdAndStatus(long ankamaUserId, AnkamaFriendLink.Status status);
}
