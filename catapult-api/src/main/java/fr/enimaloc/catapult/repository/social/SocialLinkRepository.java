package fr.enimaloc.catapult.repository.social;

import fr.enimaloc.catapult.domain.social.SocialLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SocialLinkRepository extends JpaRepository<SocialLink, Long> {
    List<SocialLink> findAllByOrderByPositionAscIdAsc();

    List<SocialLink> findByVisibleTrueOrderByPositionAscIdAsc();

    Optional<SocialLink> findFirstByOrderByPositionDesc();
}
