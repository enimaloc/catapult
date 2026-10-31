package fr.enimaloc.catapult.repository.minecraft;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MinecraftFriendLinkRepository extends JpaRepository<MinecraftFriendLink, UUID> {
    Optional<MinecraftFriendLink> findByUser(UserAccount user);

    /**
     * Variant with the service account loaded (open-in-view is disabled: readers
     * outside a transaction, such as controllers, cannot initialize the LAZY proxy).
     */
    @EntityGraph(attributePaths = "serviceAccount")
    Optional<MinecraftFriendLink> findWithServiceAccountByUser(UserAccount user);
    List<MinecraftFriendLink> findByServiceAccount(MinecraftServiceAccount serviceAccount);
    long countByServiceAccount(MinecraftServiceAccount serviceAccount);
    boolean existsByServiceAccountAndStatus(MinecraftServiceAccount serviceAccount, MinecraftFriendLink.Status status);
}
