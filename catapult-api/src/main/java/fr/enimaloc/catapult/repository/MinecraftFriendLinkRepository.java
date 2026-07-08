package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MinecraftFriendLinkRepository extends JpaRepository<MinecraftFriendLink, UUID> {
    Optional<MinecraftFriendLink> findByUser(UserAccount user);
    List<MinecraftFriendLink> findByServiceAccount(MinecraftServiceAccount serviceAccount);
    long countByServiceAccount(MinecraftServiceAccount serviceAccount);
    List<MinecraftFriendLink> findByStatus(MinecraftFriendLink.Status status);
    boolean existsByServiceAccountAndStatus(MinecraftServiceAccount serviceAccount, MinecraftFriendLink.Status status);
}
