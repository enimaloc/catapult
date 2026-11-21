package fr.enimaloc.catapult.repository.battlenet;

import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BattleNetGameVariantRepository extends JpaRepository<BattleNetGameVariant, UUID> {

    List<BattleNetGameVariant> findByEnabledTrue();

    boolean existsByFourccAndVariantKey(String fourcc, String variantKey);
}
