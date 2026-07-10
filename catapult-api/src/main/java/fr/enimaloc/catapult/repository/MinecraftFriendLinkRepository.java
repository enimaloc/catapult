package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MinecraftFriendLinkRepository extends JpaRepository<MinecraftFriendLink, UUID> {
    Optional<MinecraftFriendLink> findByUser(UserAccount user);

    /**
     * Variante avec le compte de service chargé (open-in-view désactivé : les
     * lecteurs hors transaction — contrôleurs — ne peuvent pas initialiser le proxy LAZY).
     */
    @EntityGraph(attributePaths = "serviceAccount")
    Optional<MinecraftFriendLink> findWithServiceAccountByUser(UserAccount user);
    List<MinecraftFriendLink> findByServiceAccount(MinecraftServiceAccount serviceAccount);
    long countByServiceAccount(MinecraftServiceAccount serviceAccount);
    boolean existsByServiceAccountAndStatus(MinecraftServiceAccount serviceAccount, MinecraftFriendLink.Status status);
}
